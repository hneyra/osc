-- V5: Extended metadata model (ADR-006) and Kotlin Scripting tables (ADR-005)
-- Additive only — no existing table is altered destructively.
-- All new tables carry tenant_id NOT NULL, RLS enabled, and the tenant_isolation policy.

-- ── Record Types ────────────────────────────────────────────────────────────

CREATE TABLE md_record_type (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  UUID         NOT NULL REFERENCES tenant(id),
    object_id  UUID         NOT NULL REFERENCES md_object(id) ON DELETE CASCADE,
    api_name   VARCHAR(255) NOT NULL,
    label      VARCHAR(255) NOT NULL,
    is_default BOOLEAN      NOT NULL DEFAULT FALSE,
    is_active  BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, object_id, api_name)
);

ALTER TABLE md_record_type ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON md_record_type
    USING (tenant_id = current_setting('app.current_tenant', TRUE)::UUID);

-- ── Relationships (Lookup / Master-Detail / Many-to-Many) ───────────────────

CREATE TABLE md_relationship (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID         NOT NULL REFERENCES tenant(id),
    relationship_type  VARCHAR(20)  NOT NULL,
    child_object_id    UUID         NOT NULL REFERENCES md_object(id) ON DELETE CASCADE,
    parent_object_id   UUID         NOT NULL REFERENCES md_object(id) ON DELETE CASCADE,
    field_id           UUID         REFERENCES md_field(id) ON DELETE CASCADE,
    junction_object_id UUID         REFERENCES md_object(id) ON DELETE CASCADE,
    on_delete          VARCHAR(20)  NOT NULL DEFAULT 'RESTRICT',
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (relationship_type IN ('LOOKUP', 'MASTER_DETAIL', 'MANY_TO_MANY')),
    CHECK (on_delete IN ('RESTRICT', 'CASCADE', 'SET_NULL')),
    -- MANY_TO_MANY carries junction_object_id and no field_id; LOOKUP/MASTER_DETAIL carry
    -- field_id and no junction_object_id — mirrors metadata-relationship-schema.json.
    CHECK (
        (relationship_type = 'MANY_TO_MANY' AND junction_object_id IS NOT NULL AND field_id IS NULL)
        OR (relationship_type <> 'MANY_TO_MANY' AND field_id IS NOT NULL AND junction_object_id IS NULL)
    ),
    -- MASTER_DETAIL must cascade (NNG-026: enforced transactionally in DynamicPersistenceService,
    -- not as a physical FK, since all data lives in the universal `record` table).
    CHECK (relationship_type <> 'MASTER_DETAIL' OR on_delete = 'CASCADE')
);

ALTER TABLE md_relationship ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON md_relationship
    USING (tenant_id = current_setting('app.current_tenant', TRUE)::UUID);

CREATE INDEX idx_relationship_child ON md_relationship (tenant_id, child_object_id);
CREATE INDEX idx_relationship_parent ON md_relationship (tenant_id, parent_object_id);

-- ── Layout Assignments (resolution: object default -> record type -> permission set -> both) ──

CREATE TABLE md_layout_assignment (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID        NOT NULL REFERENCES tenant(id),
    layout_id         UUID        NOT NULL REFERENCES md_layout(id) ON DELETE CASCADE,
    record_type_id    UUID        REFERENCES md_record_type(id) ON DELETE CASCADE,
    permission_set_id UUID        REFERENCES md_permission_set(id) ON DELETE CASCADE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, record_type_id, permission_set_id)
);

ALTER TABLE md_layout_assignment ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON md_layout_assignment
    USING (tenant_id = current_setting('app.current_tenant', TRUE)::UUID);

-- ── record: optional record type ─────────────────────────────────────────────
-- NULL = object's single default record type; existing rows/objects are unaffected.

ALTER TABLE record ADD COLUMN record_type_id UUID REFERENCES md_record_type(id);

-- ── Kotlin Scripting (ADR-005) ───────────────────────────────────────────────

CREATE TABLE md_script (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenant(id),
    object_id       UUID        NOT NULL REFERENCES md_object(id) ON DELETE CASCADE,
    kind            VARCHAR(20) NOT NULL,
    trigger_event   VARCHAR(20),
    invocable_name  VARCHAR(255),
    schedule_cron   VARCHAR(255),
    source          TEXT        NOT NULL,
    is_active       BOOLEAN     NOT NULL DEFAULT FALSE,
    compiled_at     TIMESTAMPTZ,
    compile_errors  JSONB       NOT NULL DEFAULT '[]',
    timeout_seconds INTEGER     NOT NULL DEFAULT 5,
    generated_by_ai BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (kind IN ('TRIGGER', 'BATCH', 'SCHEDULED', 'INVOCABLE_ACTION')),
    CHECK (kind <> 'TRIGGER' OR trigger_event IS NOT NULL),
    CHECK (kind <> 'INVOCABLE_ACTION' OR invocable_name IS NOT NULL),
    CHECK (kind <> 'SCHEDULED' OR schedule_cron IS NOT NULL),
    CHECK (timeout_seconds BETWEEN 1 AND 30),
    -- NNG-023: a script cannot be activated while compile_errors is non-empty.
    CHECK (NOT is_active OR compile_errors = '[]'::jsonb)
);

ALTER TABLE md_script ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON md_script
    USING (tenant_id = current_setting('app.current_tenant', TRUE)::UUID);

CREATE INDEX idx_script_object ON md_script (tenant_id, object_id);

CREATE TABLE script_execution_log (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenant(id),
    script_id       UUID        NOT NULL REFERENCES md_script(id) ON DELETE CASCADE,
    trigger_context VARCHAR(50),
    duration_ms     INTEGER     NOT NULL,
    outcome         VARCHAR(20) NOT NULL,
    log_output      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (outcome IN ('SUCCESS', 'FAILED', 'TIMEOUT'))
);

ALTER TABLE script_execution_log ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON script_execution_log
    USING (tenant_id = current_setting('app.current_tenant', TRUE)::UUID);

CREATE INDEX idx_script_log_script ON script_execution_log (tenant_id, script_id, created_at DESC);
