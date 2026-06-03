# OSC Agent Runbook

This document is for AI coding agents (Claude Code and similar). It provides structured task patterns, decision trees, and verification steps for common OSC development tasks.

Read this alongside `agents/CLAUDE.md` (non-negotiables and reactive rules).

---

## Before Any Task

```
1. Read docs/PROJECT.md — understand the phase plan and current status
2. Read docs/developers/non-negotiables.md — internalize the hard constraints
3. Read the relevant ADR in docs/adr/ if the task touches an architectural area
4. Read the relevant JSON Schema in docs/contracts/ if the task touches metadata
5. Identify which backend module owns the task — see docs/developers/modules-reference.md
6. Never build on a module that doesn't exist yet
```

---

## Task Pattern: Add a New Backend Feature

### Step 1 — Identify the module

Use this decision tree:

```
Does it define metadata structure?         → metadata-engine
Does it persist or retrieve records?       → persistence
Does it parse or execute queries?          → query-engine
Does it check permissions or filter FLS?   → security
Does it validate data or trigger actions?  → automation
Does it expose an HTTP endpoint?           → api
Does it call an external system?           → integrations
Does it use Spring AI?                     → ai
```

### Step 2 — Design the contract first

For non-trivial additions:
1. Define the interface (Java `interface`) before implementing it
2. If it crosses module boundaries, write the contract in a method signature
3. If it's an architectural decision, create an ADR in `docs/adr/ADR-00N-title.md`

### Step 3 — Implement reactively

```java
// CORRECT
public Mono<Result> doWork(UUID tenantId, ...) {
  return metadataEngine.getObject(tenantId, objectName)
      .flatMap(obj -> repository.query(tenantId, obj, ...))
      .map(entity -> Result.from(entity));
}

// WRONG — never use .block()
public Result doWork(UUID tenantId, ...) {
  ObjectDefinition obj = metadataEngine.getObject(tenantId, objectName).block();
  ...
}
```

### Step 4 — Multi-tenancy checklist

Before writing any repository method:
- [ ] Method accepts `UUID tenantId` as explicit parameter
- [ ] SQL includes `WHERE tenant_id = :tenantId` bind
- [ ] `tenantId` comes from the caller's Reactor Context (not hardcoded or from request body)
- [ ] New table has `tenant_id NOT NULL` + RLS policy

### Step 5 — Write tests

Minimum required per feature:
1. Unit test with `StepVerifier` (no `.block()` in tests either)
2. Integration test with TestContainers + real PostgreSQL
3. Tenant isolation test (cross-tenant read returns empty)
4. If user input reaches a query: SQL injection test

### Step 6 — Update docs if needed

- New architectural decision → create `docs/adr/ADR-00N.md`
- Changed metadata model → update `docs/contracts/*.json`
- New phase started or completed → update `docs/PROJECT.md`

---

## Task Pattern: Add a Flyway Migration

```sql
-- File: backend/persistence/src/main/resources/db/migration/V{N}__{description}.sql
-- Increment N from the last existing migration version.

-- 1. CREATE TABLE with tenant_id
CREATE TABLE new_table (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL REFERENCES tenant(id),
  -- other columns
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 2. Index on tenant_id (always)
CREATE INDEX new_table_tenant_idx ON new_table (tenant_id);

-- 3. Enable RLS (mandatory)
ALTER TABLE new_table ENABLE ROW LEVEL SECURITY;

-- 4. Create tenant isolation policy (mandatory)
CREATE POLICY new_table_tenant_isolation ON new_table
  USING (tenant_id = current_setting('app.current_tenant')::uuid);
```

Then add a migration test:
```java
// Verify RLS policy exists in FlywayMigrationV{N}Test
@Test
void new_table_has_rls_policy() { ... }
```

**Never edit an applied migration.** If you made a mistake, create `V{N+1}__fix_description.sql`.

---

## Task Pattern: Add a New REST Endpoint

1. Add method to the appropriate controller in `backend/api/src/main/java/.../api/`
2. Return `Mono<ResponseEntity<T>>` or `Flux<T>` — never a blocking type
3. Add permission check using `PermissionChecker` before any data access
4. Apply FLS using `FlsFilter` before returning record data
5. Add to OpenAPI via SpringDoc annotations (`@Operation`, `@ApiResponse`)
6. Add integration test that verifies:
   - 401 without Authorization header
   - 403 with valid token but missing permission
   - 200 with valid token and correct data
   - 404 for non-existent resource
   - Cross-tenant isolation (another tenant's resource returns 404)

---

## Task Pattern: Add a New Field Type

1. Add the enum constant to `FieldType.java` in `metadata-engine`
2. Add coercion logic to `FieldCoercionEngine.java`
3. Update `docs/contracts/metadata-field-schema.json` to include the new type
4. Update `docs/users/objects-and-fields.md` field types table
5. Add a rendering case in `frontend/renderer/src/renderer/FieldRenderer.tsx`
6. Add unit tests for coercion + renderer

---

## Task Pattern: Add an Automation Action Type

1. Add the enum constant to `ActionType.java` in `automation`
2. Implement the action handler in `DefaultAutomationEngine.java`
3. If the action is asynchronous (e.g., webhook), route through the `OutboxWorker`
4. If the action calls an external system, validate the domain against `DomainAllowlist`
5. Add audit logging via `AuditLogger`
6. Update `docs/users/automation-guide.md` action types table
7. Add unit + integration tests including failure/retry cases

---

## Task Pattern: Modify the Metadata Model

1. Check if it conflicts with the JSON Schema contract in `docs/contracts/`
2. Write a Flyway migration (`V{N}__description.sql`)
3. Update the domain model Java record/class in `metadata-engine`
4. Update the `R2dbcMetadataRepository` to map the new column
5. Update `CaffeineMetadataEngine` if cache invalidation changes
6. Update `docs/contracts/metadata-*.json`
7. Update `frontend/renderer/src/types/metadata.ts`
8. Update `docs/users/concepts.md` if the user-facing model changed
9. Add migration test

---

## Verification Checklist (run before marking any task done)

```bash
# 1. Compile
./gradlew compileJava

# 2. All tests including ArchUnit
./gradlew test

# 3. No .block() in production code (also caught by ArchUnit)
grep -r "\.block()" backend/*/src/main --include="*.java"

# 4. No raw string SQL concatenation
grep -r '\"SELECT.*\" +' backend/*/src/main --include="*.java"
grep -r '"WHERE.*" +' backend/*/src/main --include="*.java"

# 5. Frontend tests (if changed)
cd frontend/renderer && npm test

# 6. Type check frontend
cd frontend/renderer && npm run tsc --noEmit
```

---

## Common Mistakes to Avoid

| Mistake | Why it's wrong | Correct approach |
|---|---|---|
| `.block()` in production | Blocks the event loop | Use `flatMap`, `zipWith`, reactive operators |
| `ThreadLocal` for tenant context | Lost across thread switches | Use Reactor `Context` |
| `String sql = "... WHERE name='" + value + "'"` | SQL injection | Use `.bind("name", value)` |
| Query without `tenant_id` bind | Tenant data leak | Always bind `:tenantId` |
| New table without RLS | Bypass-able security | Add `ENABLE ROW LEVEL SECURITY` + policy |
| Edit existing Flyway migration | Breaks all environments | Create new V{N+1} migration |
| AI output directly applied | Unpredictable writes | Validate against JSON Schema, require user confirmation |
| Bypassing `MetadataEngine` | Cache inconsistency | Always go through the engine interface |
| `Mono.fromCallable(() -> jdbcTemplate.query(...))` | Wraps blocking I/O | Use R2DBC `DatabaseClient` |

---

## Module Quick Reference

| Module | Package | Key interface |
|---|---|---|
| `metadata-engine` | `dev.osc.metadata` | `MetadataEngine` |
| `persistence` | `dev.osc.persistence` | `DynamicPersistenceService` |
| `query-engine` | `dev.osc.queryengine` | `QueryParser`, `QueryTranslator`, `QueryExecutor` |
| `security` | `dev.osc.security` | `PermissionChecker`, `FlsFilter` |
| `automation` | `dev.osc.automation` | `ValidationEngine`, `AutomationEngine` |
| `api` | `dev.osc.api` | `OscApplication`, `DynamicRecordController` |
| `ai` | `dev.osc.ai` | `NlToMetadataService`, `NlToQueryService` |
| `integrations` | `dev.osc.integrations` | `WebhookDeliveryService` |

---

## Related Documents

| Document | When to read it |
|---|---|
| `agents/CLAUDE.md` | Before every task — reactive rules, SQL rules, code style |
| `docs/PROJECT.md` | Architecture overview, phase plan, glossary |
| `docs/developers/non-negotiables.md` | Hard constraints with rationale |
| `docs/developers/architecture.md` | Mermaid diagrams of system, modules, data flow |
| `docs/developers/database-guide.md` | Flyway, R2DBC patterns, migration templates |
| `docs/developers/security-model.md` | JWT, RLS, FLS, multi-tenancy enforcement |
| `docs/adr/` | Why each architectural decision was made |
| `docs/contracts/` | JSON Schema contracts for metadata |
