-- V6: Add md_object config, md_relationship junction_object_api_name, and many-to-many auto-creation trigger

-- Add config column to md_object to support {"is_junction": true}
ALTER TABLE md_object ADD COLUMN config JSONB NOT NULL DEFAULT '{}'::jsonb;

-- Add junction_object_api_name column to md_relationship to specify the requested API name for M2M relationships
ALTER TABLE md_relationship ADD COLUMN junction_object_api_name VARCHAR(255);

-- Create trigger function to automatically provision junction object + 2 master-detail relationships
CREATE OR REPLACE FUNCTION fn_auto_create_junction_object()
RETURNS TRIGGER AS $$
DECLARE
    v_parent_a_api_name VARCHAR(255);
    v_parent_b_api_name VARCHAR(255);
    v_junction_label VARCHAR(255);
    v_junction_label_plural VARCHAR(255);
    v_field_a_api_name VARCHAR(255);
    v_field_b_api_name VARCHAR(255);
    v_field_a_id UUID;
    v_field_b_id UUID;
    v_junction_id UUID;
BEGIN
    IF NEW.relationship_type = 'MANY_TO_MANY' AND NEW.junction_object_id IS NULL AND NEW.junction_object_api_name IS NOT NULL THEN
        v_junction_id := gen_random_uuid();

        -- Get parent api names
        SELECT api_name INTO v_parent_a_api_name FROM md_object WHERE id = NEW.child_object_id;
        SELECT api_name INTO v_parent_b_api_name FROM md_object WHERE id = NEW.parent_object_id;

        IF v_parent_a_api_name IS NULL OR v_parent_b_api_name IS NULL THEN
            RAISE EXCEPTION 'Parent objects not found for MANY_TO_MANY relationship: child_object_id=%, parent_object_id=%', NEW.child_object_id, NEW.parent_object_id;
        END IF;

        -- Generate friendly label for junction object
        v_junction_label := INITCAP(REPLACE(REPLACE(NEW.junction_object_api_name, '__c', ''), '_', ' '));
        v_junction_label_plural := v_junction_label || 's';

        -- 1. Create the junction md_object
        INSERT INTO md_object (id, tenant_id, api_name, label, label_plural, is_custom, config)
        VALUES (v_junction_id, NEW.tenant_id, NEW.junction_object_api_name, v_junction_label, v_junction_label_plural, TRUE, '{"is_junction": true}'::jsonb);

        -- 2. Derive field api names
        v_field_a_api_name := LOWER(REPLACE(v_parent_a_api_name, '__c', '')) || '_id__c';
        v_field_b_api_name := LOWER(REPLACE(v_parent_b_api_name, '__c', '')) || '_id__c';

        -- If they are the same parent (self MANY_TO_MANY), make them distinct
        IF v_field_a_api_name = v_field_b_api_name THEN
            v_field_a_api_name := LOWER(REPLACE(v_parent_a_api_name, '__c', '')) || '_a_id__c';
            v_field_b_api_name := LOWER(REPLACE(v_parent_b_api_name, '__c', '')) || '_b_id__c';
        END IF;

        -- 3. Create the two MASTER_DETAIL fields on the junction object
        v_field_a_id := gen_random_uuid();
        INSERT INTO md_field (id, tenant_id, object_id, api_name, label, field_type, storage_kind, storage_key, is_required, is_custom, config)
        VALUES (v_field_a_id, NEW.tenant_id, v_junction_id, v_field_a_api_name, INITCAP(REPLACE(v_field_a_api_name, '__c', '')), 'MASTER_DETAIL', 'JSONB', v_field_a_api_name, TRUE, TRUE, '{}'::jsonb);

        v_field_b_id := gen_random_uuid();
        INSERT INTO md_field (id, tenant_id, object_id, api_name, label, field_type, storage_kind, storage_key, is_required, is_custom, config)
        VALUES (v_field_b_id, NEW.tenant_id, v_junction_id, v_field_b_api_name, INITCAP(REPLACE(v_field_b_api_name, '__c', '')), 'MASTER_DETAIL', 'JSONB', v_field_b_api_name, TRUE, TRUE, '{}'::jsonb);

        -- 4. Create the two MASTER_DETAIL relationships from the junction object to the two parent objects
        INSERT INTO md_relationship (id, tenant_id, relationship_type, child_object_id, parent_object_id, field_id, on_delete)
        VALUES (gen_random_uuid(), NEW.tenant_id, 'MASTER_DETAIL', v_junction_id, NEW.child_object_id, v_field_a_id, 'CASCADE');

        INSERT INTO md_relationship (id, tenant_id, relationship_type, child_object_id, parent_object_id, field_id, on_delete)
        VALUES (gen_random_uuid(), NEW.tenant_id, 'MASTER_DETAIL', v_junction_id, NEW.parent_object_id, v_field_b_id, 'CASCADE');

        -- Set the junction_object_id on the record being inserted so that constraints pass
        NEW.junction_object_id := v_junction_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_auto_create_junction_object
BEFORE INSERT ON md_relationship
FOR EACH ROW
EXECUTE FUNCTION fn_auto_create_junction_object();
