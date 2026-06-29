# Database Guide

## Overview

OSC uses **PostgreSQL 16** as its sole datastore. All access is through **R2DBC** (reactive, non-blocking). Schema changes are managed exclusively via **Flyway**. Multi-tenancy is enforced by a combination of application-level filtering and PostgreSQL Row Level Security (RLS).

## Schema Overview

### Metadata Tables (Metadata Plane)

```sql
tenant                -- registered tenants
md_object             -- object type definitions (per tenant)
md_field              -- field definitions (per object, per tenant) — now incl. FORMULA, ROLLUP (ADR-006)
md_validation_rule    -- declarative validation formulas
md_layout             -- form/detail layout sections
md_layout_assignment  -- which layout applies for a given record type + permission set (ADR-006)
md_list_view          -- list view column definitions
md_automation         -- trigger/action automation definitions
md_relationship        -- Lookup / Master-Detail / Many-to-Many between objects (ADR-006)
md_record_type         -- record types per object (ADR-006)
md_script               -- Kotlin Scripting user-code: Trigger/Batch/Scheduled/Invocable Action (ADR-005)
```

### Script Execution Audit (ADR-005)

```sql
script_execution_log  -- every Kotlin script execution: script_id, trigger context, duration, outcome, log output
```

### Data Tables (Data Plane)

```sql
record                -- universal data table (JSONB)
outbox_event          -- transactional outbox for webhooks
```

### Permission Tables (Security Plane)

```sql
permission_set        -- named permission set
permission_set_assignment  -- user → permission set
object_permission     -- CRUD permissions on object per permission set
field_permission      -- read/write permissions on field per permission set
```

### Audit Tables

```sql
automation_audit_log  -- record of every automation execution
```

## Universal `record` Table

All tenant data lives in a single table. No DDL changes are required when tenants add new objects or fields.

```sql
CREATE TABLE record (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL REFERENCES tenant(id),
  object_name TEXT NOT NULL,
  data        JSONB NOT NULL DEFAULT '{}',
  owner_id    UUID,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Indexes
CREATE INDEX record_tenant_object_idx ON record (tenant_id, object_name);
CREATE INDEX record_data_gin_idx ON record USING GIN (data);

-- RLS
ALTER TABLE record ENABLE ROW LEVEL SECURITY;
CREATE POLICY record_tenant_isolation ON record
  USING (tenant_id = current_setting('app.current_tenant')::uuid);
```

### JSONB field access

Fields are stored in the `data` column. The Query Engine translates field references:

```sql
-- SOQL: SELECT name, annual_revenue FROM Account WHERE industry = 'Tech'
SELECT
  id, tenant_id, object_name, owner_id, created_at, updated_at,
  data->>'name'            AS name,
  data->>'annual_revenue'  AS annual_revenue
FROM record
WHERE tenant_id = $1
  AND object_name = 'Account'
  AND data->>'industry' = $2
```

### Column promotion

When a field's access frequency crosses a threshold (`FieldAccessCounter`), it can be promoted to a native column via a Flyway migration. The `md_field.storage_kind` column tracks this:

| `storage_kind` | Storage mechanism |
|---|---|
| `JSONB` (default) | Stored in `data` JSONB column |
| `COLUMN` | Stored in a promoted native column |

Promotion requires a Flyway migration. The Query Engine generates different SQL paths depending on `storage_kind`.

## Extended Metadata (ADR-006) and Script Tables (ADR-005)

Additive — no existing table changes destructively. Full rationale in ADR-006 and ADR-005.

```sql
ALTER TABLE record ADD COLUMN record_type_id UUID REFERENCES md_record_type(id);
-- NULL = object's single default record type; existing objects are unaffected.

CREATE TABLE md_relationship (
  id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id          UUID NOT NULL REFERENCES tenant(id),
  relationship_type  TEXT NOT NULL,           -- 'LOOKUP' | 'MASTER_DETAIL' | 'MANY_TO_MANY'
  child_object_id    UUID NOT NULL REFERENCES md_object(id),
  parent_object_id   UUID NOT NULL REFERENCES md_object(id),
  field_id           UUID REFERENCES md_field(id),
  junction_object_id UUID REFERENCES md_object(id),
  on_delete          TEXT NOT NULL DEFAULT 'RESTRICT',
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE md_record_type (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL REFERENCES tenant(id),
  object_id   UUID NOT NULL REFERENCES md_object(id),
  api_name    TEXT NOT NULL,
  label       TEXT NOT NULL,
  is_default  BOOLEAN NOT NULL DEFAULT false,
  is_active   BOOLEAN NOT NULL DEFAULT true,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, object_id, api_name)
);

CREATE TABLE md_layout_assignment (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id         UUID NOT NULL REFERENCES tenant(id),
  layout_id         UUID NOT NULL REFERENCES md_layout(id),
  record_type_id    UUID REFERENCES md_record_type(id),
  permission_set_id UUID REFERENCES permission_set(id),
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, record_type_id, permission_set_id)
);

CREATE TABLE md_script (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL REFERENCES tenant(id),
  object_id        UUID NOT NULL REFERENCES md_object(id),
  kind             TEXT NOT NULL,    -- 'TRIGGER' | 'BATCH' | 'SCHEDULED' | 'INVOCABLE_ACTION'
  trigger_event    TEXT,             -- required when kind = 'TRIGGER'
  invocable_name   TEXT,             -- required when kind = 'INVOCABLE_ACTION'
  schedule_cron    TEXT,             -- required when kind = 'SCHEDULED'
  source           TEXT NOT NULL,
  is_active        BOOLEAN NOT NULL DEFAULT false,
  compiled_at      TIMESTAMPTZ,
  compile_errors   JSONB NOT NULL DEFAULT '[]',
  timeout_seconds  INT NOT NULL DEFAULT 5,
  generated_by_ai  BOOLEAN NOT NULL DEFAULT false,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (NOT is_active OR compile_errors = '[]'::jsonb)
);

CREATE TABLE script_execution_log (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL REFERENCES tenant(id),
  script_id        UUID NOT NULL REFERENCES md_script(id),
  trigger_context  TEXT,
  duration_ms      INT NOT NULL,
  outcome          TEXT NOT NULL,    -- 'SUCCESS' | 'FAILED' | 'TIMEOUT'
  log_output       TEXT,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- All six tables: tenant_id NOT NULL, RLS enabled, tenant_isolation policy (NNG-005/006/008), same pattern as `record` above.
```

`FORMULA` and `ROLLUP` are `md_field.field_type` values, not new tables — see `docs/contracts/metadata-field-schema.json` for the `config` shape (`config.formula` / `config.rollup`).

## Flyway Conventions

### File naming

```
V{version}__{description}.sql

V1__initial_metadata_schema.sql
V2__seed_standard_objects.sql
V3__permission_schema.sql
V4__automation_audit_schema.sql
V5__extended_metadata_and_script_schema.sql
V6__add_webhook_subscriptions.sql   ← next available version
```

### Rules

1. **Never edit an applied migration.** Flyway validates checksums — modifying an applied migration breaks all existing environments.
2. **Always create a new version** for any schema change, even a small one.
3. **New tables require RLS.** Every `CREATE TABLE` migration must include `ALTER TABLE … ENABLE ROW LEVEL SECURITY` and the tenant isolation policy.
4. **New tables require `tenant_id`.** Exception: lookup/config tables shared across tenants (must be justified in an ADR).
5. **Migrations are transactional** unless DDL is used. Prefer transactional migrations.
6. **No DROP TABLE or DROP COLUMN** without an explicit data retention plan in the migration comment.

### Template for a new table migration

```sql
-- V6__add_webhook_subscriptions.sql

CREATE TABLE webhook_subscription (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id    UUID NOT NULL REFERENCES tenant(id),
  object_name  TEXT NOT NULL,
  event_types  TEXT[] NOT NULL,
  target_url   TEXT NOT NULL,
  secret       TEXT NOT NULL,
  active       BOOLEAN NOT NULL DEFAULT true,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ws_tenant_idx ON webhook_subscription (tenant_id);

ALTER TABLE webhook_subscription ENABLE ROW LEVEL SECURITY;
CREATE POLICY ws_tenant_isolation ON webhook_subscription
  USING (tenant_id = current_setting('app.current_tenant')::uuid);
```

## R2DBC Patterns

### Repository pattern

```java
@Repository
public class R2dbcRecordRepository {

  private final DatabaseClient db;

  // Always bind :tenantId explicitly
  public Mono<RecordEntity> findById(UUID tenantId, UUID id) {
    return db.sql("""
          SELECT id, tenant_id, object_name, data, owner_id, created_at, updated_at
          FROM record
          WHERE tenant_id = :tenantId AND id = :id
        """)
        .bind("tenantId", tenantId)
        .bind("id", id)
        .map(RecordMapper::map)
        .one();
  }
}
```

### Setting RLS context

Before executing any query, set the PostgreSQL session variable:

```java
db.sql("SELECT set_config('app.current_tenant', :tenantId, true)")
  .bind("tenantId", tenantId.toString())
  .fetch()
  .rowsUpdated()
  .then(/* actual query */);
```

This is handled centrally by the `R2dbcRecordRepository` — do not duplicate it in other modules.

### Transaction pattern

```java
@Service
public class DynamicPersistenceService {

  private final TransactionalOperator tx;

  public Mono<RecordEntity> create(RecordInsertCommand cmd) {
    return tx.transactional(
      repository.insert(cmd)
        .flatMap(record -> outboxRepository.enqueue(record))
    );
  }
}
```

### Pagination

```java
public Flux<RecordEntity> list(UUID tenantId, String objectName, PageRequest page) {
  return db.sql("""
        SELECT … FROM record
        WHERE tenant_id = :tenantId AND object_name = :objectName
        ORDER BY created_at DESC
        LIMIT :limit OFFSET :offset
      """)
      .bind("tenantId", tenantId)
      .bind("objectName", objectName)
      .bind("limit", page.size())
      .bind("offset", page.offset())
      .map(RecordMapper::map)
      .all();
}
```

## Multi-Tenancy Enforcement

Two independent layers enforce tenant isolation:

### Layer 1 — Application (R2DBC binds)

Every query that touches a tenant-owned table includes:

```java
.bind("tenantId", tenantId)
```

`tenantId` is always sourced from Reactor Context:

```java
return ReactiveSecurityContextHolder.getContext()
    .map(ctx -> ctx.getTenantId())
    .flatMap(tenantId -> repository.findAll(tenantId, ...));
```

### Layer 2 — Database (RLS)

Every tenant-owned table has RLS enabled. The policy checks:

```sql
USING (tenant_id = current_setting('app.current_tenant')::uuid)
```

The session variable `app.current_tenant` is set at the start of each connection, sourced from the application-level `tenantId`.

If the application layer fails to set `tenant_id` (a bug), RLS blocks the query at the DB level.

## Testing the Database Layer

### Integration tests with TestContainers

```java
@SpringBootTest
@Testcontainers
class TenantIsolationIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void tenantA_cannotReadTenantB_records() {
    // Arrange: create records for two different tenants
    // Act: query as tenant A
    // Assert: only tenant A's records are returned
  }
}
```

### SQL injection tests

```java
@Test
void sqlInjection_inFieldValue_isHarmless() {
  String maliciousValue = "'; DROP TABLE record; --";
  // create record with malicious field value
  // assert record was created safely (as a string literal)
  // assert record table still exists
}
```
