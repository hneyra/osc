-- V2: Seed standard objects (Account, Contact) for the default system tenant.
-- These objects exist as metadata records — not as Java classes.
-- The system tenant is used for development and integration tests.

INSERT INTO tenant (id, api_name, display_name)
VALUES ('00000000-0000-0000-0000-000000000001', 'system', 'System Tenant')
ON CONFLICT (api_name) DO NOTHING;

-- ── Account ───────────────────────────────────────────────────────────────

INSERT INTO md_object (id, tenant_id, api_name, label, label_plural, is_custom)
VALUES (
    '00000000-0000-0000-0001-000000000001',
    '00000000-0000-0000-0000-000000000001',
    'Account', 'Account', 'Accounts', FALSE
) ON CONFLICT (tenant_id, api_name) DO NOTHING;

INSERT INTO md_field (tenant_id, object_id, api_name, label, field_type, storage_kind, storage_key, is_required, is_custom)
VALUES
    -- system fields (promoted columns)
    ('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0001-000000000001',
     'name', 'Name', 'TEXT', 'COLUMN', 'name', TRUE, FALSE),
    -- custom fields (JSONB)
    ('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0001-000000000001',
     'industry__c', 'Industry', 'PICKLIST', 'JSONB', 'industry__c', FALSE, TRUE),
    ('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0001-000000000001',
     'website__c', 'Website', 'URL', 'JSONB', 'website__c', FALSE, TRUE),
    ('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0001-000000000001',
     'phone__c', 'Phone', 'PHONE', 'JSONB', 'phone__c', FALSE, TRUE)
ON CONFLICT (tenant_id, object_id, api_name) DO NOTHING;

-- ── Contact ───────────────────────────────────────────────────────────────

INSERT INTO md_object (id, tenant_id, api_name, label, label_plural, is_custom)
VALUES (
    '00000000-0000-0000-0001-000000000002',
    '00000000-0000-0000-0000-000000000001',
    'Contact', 'Contact', 'Contacts', FALSE
) ON CONFLICT (tenant_id, api_name) DO NOTHING;

INSERT INTO md_field (tenant_id, object_id, api_name, label, field_type, storage_kind, storage_key, is_required, is_custom)
VALUES
    ('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0001-000000000002',
     'name', 'Name', 'TEXT', 'COLUMN', 'name', TRUE, FALSE),
    ('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0001-000000000002',
     'email__c', 'Email', 'EMAIL', 'JSONB', 'email__c', FALSE, TRUE),
    ('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0001-000000000002',
     'phone__c', 'Phone', 'PHONE', 'JSONB', 'phone__c', FALSE, TRUE),
    ('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0001-000000000002',
     'account_id__c', 'Account', 'LOOKUP', 'JSONB', 'account_id__c', FALSE, TRUE)
ON CONFLICT (tenant_id, object_id, api_name) DO NOTHING;
