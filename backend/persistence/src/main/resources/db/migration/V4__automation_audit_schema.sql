-- V4: Automation audit log
-- Tracks every automation and validation rule execution for observability and debugging.

CREATE TABLE automation_audit_log (
    id                   UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            UUID        NOT NULL,
    event_type           VARCHAR(255) NOT NULL,
    automation_api_name  VARCHAR(255) NOT NULL,
    context              JSONB        NOT NULL DEFAULT '{}',
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_tenant_time ON automation_audit_log (tenant_id, created_at DESC);

ALTER TABLE automation_audit_log ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON automation_audit_log
    USING (tenant_id = current_setting('app.current_tenant', TRUE)::UUID);
