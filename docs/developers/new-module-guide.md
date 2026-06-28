# Guide: Building a New Standard Module

A "standard module" (e.g., Invoicing, Inventory, HR) is **not** a new Java/Kotlin business-object
codebase. In OSC, a standard module is a curated bundle of **metadata** — objects, fields,
relationships, record types, layouts, validation rules, and optionally Kotlin Scripting
automations — shipped via a seed migration or an installable "package." The engine (already
built: `metadata-engine`, `persistence`, `query-engine`, `security`, `api`) interprets that
metadata at runtime. You should not need to touch those modules to ship a new module.

If you find yourself writing a Java class named `Invoice` or a `WHERE object_name = 'Invoice'`
literal anywhere outside a seed migration or test fixture — stop. That's a sign the module is
being hardcoded instead of modeled.

This guide assumes familiarity with `docs/PROJECT.md`, `docs/ARCHITECTURE.md`, ADR-001/002/003
(core reactive + multi-tenant + JSONB model), ADR-006 (relationships, record types, layouts,
Formula/Rollup fields), and ADR-005 (Kotlin Scripting) where relevant.

## Step 1 — Define the objects

For each business entity in the module, define an `md_object` row plus its `md_field` rows. Do
this as data (a seed Flyway migration, or a JSON metadata package per `docs/contracts/`), not as
a Java entity class — there is no per-object table or class to create; everything lives in the
universal `record` table (ADR-002).

```sql
-- Example: seed migration for an "Invoice" object
INSERT INTO md_object (id, tenant_id, api_name, label, auditable)
VALUES (gen_random_uuid(), :tenant_id, 'Invoice__c', 'Invoice', true);

INSERT INTO md_field (id, tenant_id, object_id, api_name, label, field_type, required)
VALUES
  (gen_random_uuid(), :tenant_id, :invoice_object_id, 'invoice_number', 'Invoice Number', 'TEXT', true),
  (gen_random_uuid(), :tenant_id, :invoice_object_id, 'amount', 'Amount', 'NUMBER', true),
  (gen_random_uuid(), :tenant_id, :invoice_object_id, 'status', 'Status', 'PICKLIST', true);
```

Validate every field/object definition against `docs/contracts/metadata-object-schema.json` and
`metadata-field-schema.json` before inserting — this is the same contract the AI proposal layer
and the admin UI use, so your module's metadata is consistent with everything else in the system.

## Step 2 — Model relationships

If your module's objects relate to existing ones (e.g., `Invoice__c` → `Account`), add an
`md_relationship` row (ADR-006). Use:

- **LOOKUP** for a soft reference (`on_delete = RESTRICT` or `SET_NULL`).
- **MASTER_DETAIL** when the child's lifecycle is owned by the parent (`on_delete = CASCADE`,
  enforced transactionally in `DynamicPersistenceService` per NNG-026 — there is no DB-level FK
  cascade, because everything lives in `record`).
- **MANY_TO_MANY** when two objects relate via a junction — the junction is itself an
  auto-created `md_object` with `config.is_junction = true` and two Master-Detail relationships.
  See ADR-006 for the junction-object pattern.

Validate against `docs/contracts/metadata-relationship-schema.json`.

## Step 3 — Add record types (if needed)

If the module needs different page layouts/picklist values per "kind" of record (e.g.,
`Invoice__c` having `Standard` vs `Credit Note` record types), add `md_record_type` rows and
corresponding `md_layout_assignment` rows. Resolution order for which layout/permission-set
applies is most-specific-wins — see `docs/contracts/metadata-layout-assignment-schema.json` and
ADR-006. If the object only ever has one kind of record, skip this step; `record_type_id` stays
`NULL` and the object's single default layout applies.

## Step 4 — Computed fields: Formula vs Rollup vs Kotlin Script

Before reaching for a script, check whether a declarative field type covers the need:

| Need | Use |
|---|---|
| A value computed from other fields on the **same record**, read-time | `FORMULA` field (`md_field.field_type = 'FORMULA'`, evaluated via the existing whitelist DSL — see `docs/contracts/metadata-field-schema.json` `config.formula`) |
| An aggregate computed from **child records** across a Master-Detail relationship | `ROLLUP` field (`config.rollup`: `relationshipId`, `aggregate`, `sourceFieldApiName`, optional `filterExpression`) — computed asynchronously via the outbox/AFTER pipeline (NNG-027), never synchronously on the child write |
| Cross-object logic, external calls, multi-record imperative logic, or anything the DSL whitelist can't express | Kotlin Scripting (Step 5) |

Formula and Rollup fields require no code — only `md_field` metadata. Reach for Kotlin Scripting
only when declarative metadata genuinely can't express the requirement.

## Step 5 — Imperative logic via Kotlin Scripting (ADR-005)

When the module needs a Trigger, Batch job, Scheduled job, or Invocable Action, write it as a
Kotlin Scripting unit (`md_script`), not as a new Java class in `automation` or `api`. This keeps
the module's logic in the tenant-editable metadata plane and reuses the existing compiler,
sandbox, and FLS/RLS enforcement.

```kotlin
// Example md_script.source for a TRIGGER on Invoice__c, BEFORE_INSERT
fun execute(ctx: ExecutionContext) {
    val invoice = ctx.trigger.newRecord
    if (invoice.getNumber("amount") <= 0) {
        ctx.trigger.addError("amount must be positive")
    }
}
```

Key constraints to remember (see `docs/developers/non-negotiables.md` NNG-023..025 and ADR-005):

- The script is compiled **on save**. `is_active` cannot become `true` while `compile_errors` is
  non-empty — there's a DB `CHECK` constraint backing this, not just application logic.
- The script executes with the **invoking user's** `SecurityContext` — `ExecutionContext`/
  `RecordOperations` enforce the same FLS/RLS as the REST API path. A module's script can never
  grant itself elevated access.
- If the script source was AI-generated, it persists with `generated_by_ai = true` and
  `is_active = false` — only an explicit human action activates it.
- Insert the row via `docs/contracts/metadata-script-schema.json` validation, same as any other
  metadata write.

## Step 6 — Wire up list views and layouts

Add `md_list_view` and `md_layout` rows so the new objects are browsable/editable in the runtime
UI. No frontend component code is needed — `LayoutRenderer`/`ListViewRenderer`
(`frontend/renderer/`) interpret these definitions, the same way they render every other object.

## Step 7 — Permissions

Add `object_permission` and `field_permission` rows for the relevant `permission_set`s so the
module's objects/fields are actually visible/editable to the roles that should use them. A module
without permission rows is invisible to everyone except a profile with blanket access.

## Step 8 — Tests

A standard module ships as metadata, but its **migrations and any scripts still need tests**,
per `docs/developers/non-negotiables.md` NNG-021:

- A migration test asserting the seeded objects/fields/relationships exist and validate against
  the JSON Schema contracts.
- A tenant isolation test if the module introduces any new query path.
- For each Kotlin script: a `ScriptPermissionEnforcementTest`-style case proving the script
  can't bypass FLS/RLS, plus a basic execution test via `ScriptExecutionAuditor` asserting the
  audit row is written in the same transaction as the triggering record write.
- If any rollup or formula field is added, a recomputation/evaluation test per
  `RollupRecalculationIntegrationTest` conventions.

## Checklist

- [ ] Objects/fields defined as metadata, validated against `metadata-object-schema.json` /
      `metadata-field-schema.json`
- [ ] Relationships modeled via `md_relationship`, validated against
      `metadata-relationship-schema.json`
- [ ] Record types / layout assignments added only if the object genuinely needs them
- [ ] Computed values use FORMULA/ROLLUP before reaching for a script
- [ ] Any imperative logic is a Kotlin Script (`md_script`), not new Java/Kotlin business classes
- [ ] List views, layouts, and permission sets wired so the module is actually usable
- [ ] Tests cover migration integrity, tenant isolation, and (if scripted) permission enforcement
- [ ] No new table was added unless the module needs a genuinely new metadata or audit construct
      (data itself always lives in `record`)
