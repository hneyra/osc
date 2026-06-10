# ADR-002: Storage Strategy — JSONB Universal with Column Promotion

**Status:** Accepted
**Date:** 2026-05-24
**Deciders:** Project Lead

## Context

Custom fields (defined by tenants at runtime) need to be stored somehow. Options:
1. Dynamic DDL — `ALTER TABLE` per new field
2. EAV (Entity-Attribute-Value) table
3. JSONB column on a universal record table
4. Separate table per object type (DDL-generated)

## Decision

**JSONB universal table** with explicit column promotion path.

```sql
CREATE TABLE record (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  UUID NOT NULL,
    object_id  UUID NOT NULL,
    name       TEXT,           -- standard, always a real column
    owner_id   UUID,
    data       JSONB NOT NULL DEFAULT '{}',  -- all custom fields
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_record_data_gin ON record USING GIN (data jsonb_path_ops);
```

The `md_field` table carries `storage_kind` ('JSONB' | 'COLUMN') and `storage_key` (column name or JSONB key), enabling future promotion of hot fields to real columns via Flyway migration — without changing the API contract.

**No dynamic DDL at runtime.** All schema changes go through versioned Flyway migrations applied by operators.

## Consequences

**Good:**
- No DDL at runtime — no lock contention, no migration race conditions.
- Works perfectly in multi-tenant: no table explosion (tenants × objects).
- GIN index makes JSONB queries efficient at our scale.
- Promotion path preserves API compatibility.
- Operationally simple: one table to back up, vacuum, monitor.

**Bad:**
- Complex multi-column SQL joins and reports are harder with JSONB than native columns.
- Type enforcement is application-level for JSONB fields (not DB constraints).
- GIN index won't help for range queries on JSONB fields — those hot fields should be promoted.

## Constraints

- Field type coercion (TEXT, NUMBER, DATE, BOOLEAN, etc.) is done at the application layer before writing to JSONB.
- The `Query Engine` handles the translation: `field__c` → `data->>'field__c'` with appropriate PostgreSQL cast.
- Uniqueness constraints on JSONB fields are enforced at application level (not DB).

## Implementation references

- `backend/persistence/src/main/resources/db/migration/V1__initial_metadata_schema.sql` — `record` table with `data JSONB`, the `idx_record_data_gin` GIN index, and the `idx_record_tenant_object` composite index.
- `backend/metadata-engine/src/main/java/dev/osc/metadata/StorageKind.java` — the `COLUMN` / `JSONB` enum driving the promotion path; `FieldDefinition.storageKind()` carries it per field.
- `backend/metadata-engine/src/main/java/dev/osc/metadata/DefaultFieldCoercionEngine.java` — application-level type coercion before writing JSONB.
- `backend/metadata-engine/src/main/java/dev/osc/metadata/performance/FieldAccessCounter.java` + `HotFieldReport.java` — track hot JSONB fields that are candidates for column promotion.
