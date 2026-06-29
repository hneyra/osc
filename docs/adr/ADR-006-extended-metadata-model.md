# ADR-006: Extended Dynamic Metadata Model — Relationships, Record Types, Page Layouts, Formula/Rollup Fields

**Status:** Accepted
**Date:** 2026-06-28
**Deciders:** Project Lead
**Builds on:** ADR-002 (JSONB storage), ADR-005 (Kotlin Scripting Engine)

## Context

`docs/PROJECT.md` Fase 0 shipped the minimal metadata model: `md_object`, `md_field`, `md_validation_rule`, `md_layout`, `md_list_view`, `md_automation`. The platform is repositioning as `osc-platform`, an ERP-grade engine, which requires Salesforce-equivalent metadata capability beyond that minimal set:

- **Relationships** between objects: today `md_field.reference_to` only supports a bare lookup pointer; there is no concept of cascade-delete (Master-Detail) or many-to-many junction objects.
- **Record Types**: a single object (e.g. `Opportunity__c`) needs different picklist value sets, different page layouts, and different validation rules depending on a record's "type" (e.g. `NewBusiness` vs `Renewal`).
- **Page Layouts per Record Type**: `md_layout` exists but has no notion of "which layout applies to which record type for which profile."
- **Formula and Rollup fields**: `md_field.field_type` has no entry for a computed field whose value is derived from other fields (Formula) or aggregated from child records (Rollup) — both are core to an ERP data model (e.g., `total_amount__c` rolled up from line items).
- **Field-Level Security (FLS)** already exists (`field_permission` table, per `docs/developers/security-model.md` and `docs/developers/database-guide.md`) — this ADR does not change FLS, only adds object-level relationship integrity so FLS/RLS continue to apply correctly across relationships.

This ADR specifies the additive metadata tables and `md_field.field_type`/`config` extensions needed, while preserving every constraint from ADR-001 (RLS), ADR-002 (JSONB-first, no runtime DDL), and ADR-003 (reactive/R2DBC only).

## Decision

### 1. Relationships (`md_relationship`)

```sql
CREATE TABLE md_relationship (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL REFERENCES tenant(id),
    relationship_type TEXT NOT NULL,   -- 'LOOKUP' | 'MASTER_DETAIL' | 'MANY_TO_MANY'
    child_object_id   UUID NOT NULL REFERENCES md_object(id),
    parent_object_id  UUID NOT NULL REFERENCES md_object(id),
    field_id          UUID REFERENCES md_field(id),       -- the lookup/master-detail field on the child (NULL for MANY_TO_MANY)
    junction_object_id UUID REFERENCES md_object(id),      -- the auto-created junction object (MANY_TO_MANY only)
    on_delete         TEXT NOT NULL DEFAULT 'RESTRICT',     -- 'CASCADE' | 'RESTRICT' | 'SET_NULL' (MASTER_DETAIL forces CASCADE)
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (relationship_type <> 'MANY_TO_MANY' OR junction_object_id IS NOT NULL),
    CHECK (relationship_type = 'MANY_TO_MANY' OR field_id IS NOT NULL)
);

CREATE INDEX idx_md_relationship_tenant ON md_relationship (tenant_id);
ALTER TABLE md_relationship ENABLE ROW LEVEL SECURITY;
CREATE POLICY md_relationship_tenant_isolation ON md_relationship
    USING (tenant_id = current_setting('app.current_tenant')::uuid);
```

- **Lookup**: `field_id` points at a `md_field` with `field_type = 'LOOKUP'`; `on_delete` defaults to `RESTRICT` or `SET_NULL`, tenant-configurable.
- **Master-Detail**: same shape, `on_delete` forced to `CASCADE` at the application layer (the `DynamicPersistenceService` enforces cascade delete transactionally — not via a DB `ON DELETE CASCADE` foreign key, because the parent/child relationship is metadata-described, not a physical FK between fixed tables; both live in the universal `record` table distinguished by `object_id`). Master-Detail also implies the child inherits the parent's sharing/owner for record-level access (`docs/developers/security-model.md` §5).
- **Many-to-Many**: modeled as an auto-created junction `md_object` (`is_custom = true`, hidden from end-user object list) with two Master-Detail relationships to the two related objects — no new junction-table SQL concept is introduced; a junction object is a normal object whose records live in `record` like any other.

### 2. Record Types (`md_record_type`)

```sql
CREATE TABLE md_record_type (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL REFERENCES tenant(id),
    object_id     UUID NOT NULL REFERENCES md_object(id),
    api_name      TEXT NOT NULL,
    label         TEXT NOT NULL,
    is_default    BOOLEAN NOT NULL DEFAULT false,
    is_active     BOOLEAN NOT NULL DEFAULT true,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, object_id, api_name)
);

CREATE INDEX idx_md_record_type_tenant_object ON md_record_type (tenant_id, object_id);
ALTER TABLE md_record_type ENABLE ROW LEVEL SECURITY;
CREATE POLICY md_record_type_tenant_isolation ON md_record_type
    USING (tenant_id = current_setting('app.current_tenant')::uuid);
```

The `record` table (ADR-002) gains a nullable `record_type_id UUID REFERENCES md_record_type(id)` column. `NULL` means "the object's single default record type" — objects that never define record types behave exactly as before this ADR (additive, non-breaking).

### 3. Page Layout assignment (`md_layout_assignment`)

`md_layout` (existing) already stores the layout *definition* (sections/columns/fields). What was missing is *which layout applies when*, given a record type and a profile/permission set:

```sql
CREATE TABLE md_layout_assignment (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL REFERENCES tenant(id),
    layout_id         UUID NOT NULL REFERENCES md_layout(id),
    record_type_id    UUID REFERENCES md_record_type(id),     -- NULL = applies to all record types
    permission_set_id UUID REFERENCES permission_set(id),     -- NULL = applies to all profiles
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, record_type_id, permission_set_id)
);

CREATE INDEX idx_md_layout_assignment_tenant ON md_layout_assignment (tenant_id);
ALTER TABLE md_layout_assignment ENABLE ROW LEVEL SECURITY;
CREATE POLICY md_layout_assignment_tenant_isolation ON md_layout_assignment
    USING (tenant_id = current_setting('app.current_tenant')::uuid);
```

Resolution order when rendering a record: most specific match wins — `(record_type_id, permission_set_id)` > `(record_type_id, NULL)` > `(NULL, permission_set_id)` > `(NULL, NULL)` (the object's default layout).

### 4. Formula and Rollup fields

No new table — these are new `md_field.field_type` values with structured `config` JSONB:

```sql
-- field_type = 'FORMULA'
-- config: { "expression": "{amount__c} * {tax_rate__c}", "return_type": "NUMBER" }

-- field_type = 'ROLLUP'
-- config: {
--   "relationship_id": "<md_relationship.id of the child relationship>",
--   "aggregate": "SUM" | "COUNT" | "MIN" | "MAX" | "AVG",
--   "source_field_api_name": "amount__c",      -- omitted for COUNT
--   "filter_expression": "{stage__c} = 'WON'"  -- optional, same DSL as Validation Rules
-- }
```

- `FORMULA` fields are **always computed at read time** by the Query Engine (never persisted) — same DSL expression evaluator from `docs/PROJECT.md` §6.1, evaluated against the current record's already-resolved field values. They are storage-less: `storage_kind` is irrelevant/`NULL` for `FORMULA` fields.
- `ROLLUP` fields are recomputed asynchronously by an `automation` worker whenever a child record (per `md_relationship`) is created/updated/deleted, and the result is persisted to the parent's `data` JSONB (or promoted column) like any other field, via the same outbox-driven AFTER pipeline already described in `docs/PROJECT.md` §6.2 step 8 — never computed synchronously in the request path, to avoid fan-out latency on every child write.
- Both reuse the existing whitelist DSL evaluator (`docs/PROJECT.md` §6.1) — no Kotlin Scripting involvement, consistent with ADR-005's "DSL for the simple/safe case, Kotlin Scripting for imperative logic" split.

### 5. Field-Level Security — no change, integrity note only

`field_permission` (existing, per `docs/developers/database-guide.md`) continues to gate read/write per field per permission set. This ADR's only FLS-relevant addition: a `LOOKUP`/`MASTER_DETAIL` field's FLS check also implicitly requires `READ` on the parent object — a user who can see the lookup field but has no `READ` permission on the parent object type sees the raw foreign key but the UI must not attempt to resolve/display the parent record's name. This is an `FlsFilter` behavior note, not a schema change.

## Consequences

**Good:**
- Fully additive — no existing table is altered destructively; `record.record_type_id` is nullable, existing objects without record types are unaffected.
- Junction-object modeling for Many-to-Many reuses the universal `record`/`md_object` machinery instead of inventing a second storage mechanism — consistent with ADR-002.
- Rollup fields are computed off the critical write path (outbox/AFTER), preserving the "AI/derived-computation never blocks the primary transaction" principle already established for AI (ADR-... / §8) and webhooks (§9).

**Bad / Risks:**
- Master-Detail cascade delete is implemented in application code (`DynamicPersistenceService`), not a DB-level `ON DELETE CASCADE`, because there is no physical per-object table to attach an FK to. This means cascade integrity is only as strong as the transactional logic in `persistence` — requires a dedicated `MasterDetailCascadeIntegrationTest` (tracked under Fase 1 extension, see `docs/PROJECT.md`).
- Rollup recomputation is eventually consistent (post-commit, async) — a parent's rollup field can be briefly stale immediately after a child write. This must be documented in the public API/contract so client UIs don't assume read-after-write consistency for `ROLLUP` fields.
- Formula fields referencing a `LOOKUP`'s parent fields (cross-object formulas) are explicitly **out of scope for v1** — only same-object field references are supported in `expression`, to avoid the Query Engine needing recursive joins through arbitrary relationship depth. Revisit if a real use case requires it.

## Constraints

- Every new table follows NNG-005/NNG-006/NNG-008: `tenant_id NOT NULL`, explicit app-level filter, RLS policy.
- `docs/contracts/metadata-field-schema.json` must be updated to add `FORMULA`/`ROLLUP` to the field-type enum and their `config` shapes (NNG-015).
- New contracts added: `docs/contracts/metadata-relationship-schema.json`, `docs/contracts/metadata-record-type-schema.json`.
- Many-to-Many junction objects are marked with a `md_object.config` flag (`{"is_junction": true}`) so the API/UI layer can hide them from the standard object picker while still exposing them through the dynamic REST API for direct queries if needed.

## Implementation references

Not yet implemented — this ADR specifies the schema ahead of the Flyway migration that will introduce it (tracked in `docs/PROJECT.md` Fase 1 extension / Fase 4).
