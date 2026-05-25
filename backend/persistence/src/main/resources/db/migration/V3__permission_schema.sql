-- V3: Permission & Security schema
-- Implements Field-Level Security (FLS) and Object-Level Security (OLS).
-- Every row carries tenant_id; RLS policies isolate tenants at the DB level.

-- ── Permission Sets ───────────────────────────────────────────────────────────

CREATE TABLE md_permission_set (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID         NOT NULL REFERENCES tenant(id),
    api_name    VARCHAR(255) NOT NULL,
    label       VARCHAR(255) NOT NULL,
    description TEXT,
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, api_name)
);

ALTER TABLE md_permission_set ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON md_permission_set
    USING (tenant_id = current_setting('app.current_tenant', TRUE)::UUID);

-- ── Object-Level Permissions ───────────────────────────────────────────────────

CREATE TABLE md_object_permission (
    id                UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID    NOT NULL,
    permission_set_id UUID    NOT NULL REFERENCES md_permission_set(id) ON DELETE CASCADE,
    object_id         UUID    NOT NULL REFERENCES md_object(id) ON DELETE CASCADE,
    can_read          BOOLEAN NOT NULL DEFAULT FALSE,
    can_create        BOOLEAN NOT NULL DEFAULT FALSE,
    can_edit          BOOLEAN NOT NULL DEFAULT FALSE,
    can_delete        BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (permission_set_id, object_id)
);

ALTER TABLE md_object_permission ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON md_object_permission
    USING (tenant_id = current_setting('app.current_tenant', TRUE)::UUID);

-- ── Field-Level Permissions ────────────────────────────────────────────────────

CREATE TABLE md_field_permission (
    id                UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID    NOT NULL,
    permission_set_id UUID    NOT NULL REFERENCES md_permission_set(id) ON DELETE CASCADE,
    field_id          UUID    NOT NULL REFERENCES md_field(id) ON DELETE CASCADE,
    field_api_name    VARCHAR(255) NOT NULL,
    can_read          BOOLEAN NOT NULL DEFAULT FALSE,
    can_edit          BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (permission_set_id, field_id)
);

ALTER TABLE md_field_permission ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON md_field_permission
    USING (tenant_id = current_setting('app.current_tenant', TRUE)::UUID);

-- ── User → Permission Set assignments ─────────────────────────────────────────

CREATE TABLE md_user_permission_set (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL,
    user_id           UUID NOT NULL,
    permission_set_id UUID NOT NULL REFERENCES md_permission_set(id) ON DELETE CASCADE,
    UNIQUE (tenant_id, user_id, permission_set_id)
);

ALTER TABLE md_user_permission_set ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON md_user_permission_set
    USING (tenant_id = current_setting('app.current_tenant', TRUE)::UUID);

-- ── Indexes ────────────────────────────────────────────────────────────────────

-- Fast lookup: all permission sets for a user within a tenant
CREATE INDEX idx_user_ps_tenant_user ON md_user_permission_set (tenant_id, user_id);

-- Fast lookup: field permissions for a given permission set
CREATE INDEX idx_field_perm_ps ON md_field_permission (permission_set_id);

-- Fast lookup: object permissions for a given permission set
CREATE INDEX idx_obj_perm_ps ON md_object_permission (permission_set_id);
