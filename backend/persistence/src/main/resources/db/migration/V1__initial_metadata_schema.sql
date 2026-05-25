-- V1: Core metadata schema
-- All tables except 'tenant' carry tenant_id NOT NULL.
-- RLS policies enforce tenant isolation at the database level.
-- Application layer must also set: SET LOCAL app.current_tenant = '<uuid>';

-- ── Tenant ────────────────────────────────────────────────────────────────

CREATE TABLE tenant (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    api_name     VARCHAR(255) NOT NULL UNIQUE,
    display_name VARCHAR(255) NOT NULL,
    is_active    BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ── Metadata: Objects ─────────────────────────────────────────────────────

CREATE TABLE md_object (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID        NOT NULL REFERENCES tenant(id),
    api_name     VARCHAR(255) NOT NULL,
    label        VARCHAR(255) NOT NULL,
    label_plural VARCHAR(255) NOT NULL,
    is_custom    BOOLEAN      NOT NULL DEFAULT FALSE,
    description  TEXT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, api_name)
);

ALTER TABLE md_object ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON md_object
    USING (tenant_id = current_setting('app.current_tenant', TRUE)::UUID);

-- ── Metadata: Fields ──────────────────────────────────────────────────────

CREATE TABLE md_field (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID        NOT NULL,
    object_id    UUID        NOT NULL REFERENCES md_object(id) ON DELETE CASCADE,
    api_name     VARCHAR(255) NOT NULL,
    label        VARCHAR(255) NOT NULL,
    field_type   VARCHAR(50)  NOT NULL,
    storage_kind VARCHAR(20)  NOT NULL DEFAULT 'JSONB',
    storage_key  VARCHAR(255),
    is_required  BOOLEAN      NOT NULL DEFAULT FALSE,
    is_custom    BOOLEAN      NOT NULL DEFAULT FALSE,
    config       JSONB,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, object_id, api_name)
);

ALTER TABLE md_field ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON md_field
    USING (tenant_id = current_setting('app.current_tenant', TRUE)::UUID);

-- ── Metadata: Validation Rules ────────────────────────────────────────────

CREATE TABLE md_validation_rule (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID        NOT NULL,
    object_id     UUID        NOT NULL REFERENCES md_object(id) ON DELETE CASCADE,
    api_name      VARCHAR(255) NOT NULL,
    condition_dsl TEXT        NOT NULL,
    error_message TEXT        NOT NULL,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, object_id, api_name)
);

ALTER TABLE md_validation_rule ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON md_validation_rule
    USING (tenant_id = current_setting('app.current_tenant', TRUE)::UUID);

-- ── Metadata: Layouts ─────────────────────────────────────────────────────

CREATE TABLE md_layout (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID        NOT NULL,
    object_id   UUID        NOT NULL REFERENCES md_object(id) ON DELETE CASCADE,
    api_name    VARCHAR(255) NOT NULL,
    layout_type VARCHAR(50)  NOT NULL,
    definition  JSONB        NOT NULL,
    is_default  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, object_id, api_name)
);

ALTER TABLE md_layout ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON md_layout
    USING (tenant_id = current_setting('app.current_tenant', TRUE)::UUID);

-- ── Metadata: List Views ──────────────────────────────────────────────────

CREATE TABLE md_list_view (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  UUID        NOT NULL,
    object_id  UUID        NOT NULL REFERENCES md_object(id) ON DELETE CASCADE,
    api_name   VARCHAR(255) NOT NULL,
    definition JSONB        NOT NULL,
    is_default BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, object_id, api_name)
);

ALTER TABLE md_list_view ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON md_list_view
    USING (tenant_id = current_setting('app.current_tenant', TRUE)::UUID);

-- ── Metadata: Automations ─────────────────────────────────────────────────

CREATE TABLE md_automation (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID        NOT NULL,
    object_id    UUID        NOT NULL REFERENCES md_object(id) ON DELETE CASCADE,
    api_name     VARCHAR(255) NOT NULL,
    trigger_type VARCHAR(50)  NOT NULL,
    definition   JSONB        NOT NULL,
    is_active    BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, object_id, api_name)
);

ALTER TABLE md_automation ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON md_automation
    USING (tenant_id = current_setting('app.current_tenant', TRUE)::UUID);

-- ── Universal Record Table ────────────────────────────────────────────────

CREATE TABLE record (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  UUID        NOT NULL,
    object_id  UUID        NOT NULL REFERENCES md_object(id),
    name       VARCHAR(255),
    owner_id   UUID,
    data       JSONB        NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Composite index for tenant+object scoped queries
CREATE INDEX idx_record_tenant_object ON record (tenant_id, object_id);
-- GIN index for JSONB field queries
CREATE INDEX idx_record_data_gin ON record USING GIN (data jsonb_path_ops);

ALTER TABLE record ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON record
    USING (tenant_id = current_setting('app.current_tenant', TRUE)::UUID);

-- ── Outbox Events ─────────────────────────────────────────────────────────

CREATE TABLE outbox_event (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID        NOT NULL,
    aggregate_type VARCHAR(255) NOT NULL,
    aggregate_id   UUID        NOT NULL,
    event_type     VARCHAR(255) NOT NULL,
    payload        JSONB        NOT NULL,
    status         VARCHAR(50)  NOT NULL DEFAULT 'PENDING',
    attempts       INTEGER      NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    processed_at   TIMESTAMPTZ
);

-- Partial index: only pending events need to be queried by the worker
CREATE INDEX idx_outbox_pending ON outbox_event (created_at)
    WHERE status = 'PENDING';

ALTER TABLE outbox_event ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON outbox_event
    USING (tenant_id = current_setting('app.current_tenant', TRUE)::UUID);
