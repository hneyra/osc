# OSC — Detailed Technical Architecture

## 1. Stack Details

### 1.1 Backend

- **Java 25**: Uses Virtual Threads, sealed classes, pattern matching, structured concurrency.
- **Spring Boot 4.x**: Auto-configuration, actuator, DevTools.
- **Spring WebFlux**: Netty-based reactive HTTP server. All endpoints return `Mono<ResponseEntity<T>>` or `Flux<T>`.
- **R2DBC (io.r2dbc:r2dbc-postgresql)**: Reactive, non-blocking PostgreSQL driver. All database operations return `Mono` or `Flux`.
- **Spring AI 2 M6**: AI model abstraction; used only as a productivity layer, never on the critical data path.
- **Flyway**: Database migrations. Reactive-compatible (synchronous migration at startup before reactive context is active).
- **Caffeine**: In-process metadata cache with reactive wrappers (`AsyncLoadingCache`).

### 1.2 Build System

Gradle multi-project build with Kotlin DSL:

```
osc/
├── settings.gradle.kts          # includes all subprojects
├── build.gradle.kts             # root conventions
├── gradle/
│   └── libs.versions.toml       # version catalog
├── backend/
│   ├── metadata-engine/
│   │   └── build.gradle.kts
│   ├── persistence/
│   │   └── build.gradle.kts
│   ├── query-engine/
│   │   └── build.gradle.kts
│   ├── automation/
│   │   └── build.gradle.kts
│   ├── security/
│   │   └── build.gradle.kts
│   ├── api/
│   │   └── build.gradle.kts
│   ├── ai/
│   │   └── build.gradle.kts
│   └── integrations/
│       └── build.gradle.kts
├── frontend/
│   ├── design-system/
│   ├── renderer/
│   ├── admin/
│   └── runtime/
└── infrastructure/              # Pulumi TypeScript
```

### 1.3 Database

PostgreSQL 16+ with:
- **Row-Level Security (RLS)**: Enforced per table, per tenant.
- **JSONB**: Flexible storage for custom fields, GIN-indexed.
- **GIN indexes**: On `data jsonb_path_ops` for efficient JSONB queries.
- **UUID primary keys**: `gen_random_uuid()`.

### 1.4 Infrastructure

Pulumi TypeScript in `infrastructure/`. References existing services from `hneyra/iaac`. Stacks: `dev`, `prod`.

## 2. Reactive Programming Model

### 2.1 Rules

- **Never block**: No `Mono.block()`, `Flux.toStream()`, or any blocking I/O on the event loop.
- **Virtual threads**: Spring Boot 4 + Java 25 virtual threads for CPU-bound work that cannot be reactive.
- **R2DBC everywhere**: All database interactions use `DatabaseClient` or R2DBC repositories.
- **Context propagation**: `tenant_id` propagated via Reactor `Context`, not ThreadLocal.

### 2.2 Tenant Context in Reactive Pipelines

```java
// Setting tenant context at request boundary
Mono<T> withTenant(String tenantId, Mono<T> operation) {
    return operation.contextWrite(ctx -> ctx.put(TENANT_KEY, tenantId));
}

// Retrieving in downstream operations
Mono<String> currentTenant() {
    return Mono.deferContextual(ctx -> Mono.just(ctx.get(TENANT_KEY)));
}

// Setting PostgreSQL session variable (R2DBC)
Mono<Void> setTenantSession(Connection conn, String tenantId) {
    return Mono.from(conn.createStatement("SET LOCAL app.current_tenant = $1")
        .bind("$1", tenantId)
        .execute())
        .then();
}
```

## 3. Metadata Engine

### 3.1 Responsibility

Loads, caches, and invalidates metadata for all tenants. This is the most read-intensive component.

### 3.2 Caching Strategy

```java
// Async Caffeine cache with reactive loader
AsyncLoadingCache<MetadataKey, ObjectDefinition> cache = Caffeine.newBuilder()
    .maximumSize(10_000)
    .expireAfterWrite(Duration.ofMinutes(10))
    .buildAsync(key -> loadFromDb(key).toFuture());
```

Cache invalidation: when metadata is written, publish an invalidation event (outbox pattern). All nodes flush their local cache entry.

### 3.3 Metadata Key

`MetadataKey = (tenant_id, api_name)` for objects; `(tenant_id, object_id, api_name)` for fields.

## 4. Dynamic Persistence Layer

### 4.1 Storage Strategy

| Field category | Storage |
|---|---|
| System fields | Real columns (`id`, `tenant_id`, `created_at`, `updated_at`, `owner_id`, `name`) |
| Core/common custom fields | JSONB `data` column |
| Hot fields (promoted) | Real columns added via Flyway migration (no dynamic DDL) |

### 4.2 R2DBC JSONB Access

```java
// Writing JSONB field
DatabaseClient.create(connectionFactory)
    .sql("UPDATE record SET data = data || $1::jsonb WHERE id = $2 AND tenant_id = $3")
    .bind("$1", Json.of(objectMapper.writeValueAsString(fieldUpdates)))
    .bind("$2", recordId)
    .bind("$3", tenantId)
    .fetch().rowsUpdated();

// Reading JSONB field
.sql("SELECT data->>'due_date__c' AS due_date FROM record WHERE ...")
```

### 4.3 No Dynamic DDL

The system never issues `CREATE TABLE`, `ALTER TABLE`, or `CREATE INDEX` at runtime. All schema changes go through versioned Flyway migrations. Custom fields always go to JSONB until manually promoted by an operator via a Flyway migration.

## 5. Query Engine

### 5.1 Input / Output

- Input: SOQL-like query string (e.g., `SELECT name, due_date__c FROM Project__c WHERE status__c = 'OPEN' ORDER BY created_at DESC LIMIT 50`)
- Output: `Flux<Map<String, Object>>` — reactive stream of typed records.

### 5.2 Security Constraints

1. **Always validate** object/field names against metadata (rejects unknown names).
2. **Always enforce** tenant filter — query gets injected `AND tenant_id = $tenant` regardless of user input.
3. **Always use** R2DBC parameterized bindings — never string concatenation.
4. **FLS check** — fields the user has no access to are removed from SELECT before execution.

### 5.3 Translation Example

```
Input:  SELECT name, due_date__c FROM Project__c WHERE status__c = 'OPEN'

Output: SELECT r.name, r.data->>'due_date__c' AS "due_date__c"
        FROM record r
        WHERE r.tenant_id = $1
          AND r.object_id = $2
          AND r.data->>'status__c' = $3
```

Bindings: `[$tenantId, $projectObjectId, 'OPEN']`

## 6. Security Model

### 6.1 Layers (Defense in Depth)

1. **JWT validation** — Spring Security, tenant claim extracted.
2. **Tenant context** — set on Reactor Context, propagated to R2DBC session (`SET LOCAL`).
3. **PostgreSQL RLS** — database-level policy blocks cross-tenant access.
4. **Application filter** — explicit `AND tenant_id = $tenantId` in every query.
5. **FLS (Field-Level Security)** — fields stripped from queries/responses based on permission sets.
6. **RLS (Record-Level Security)** — ownership/sharing rules applied as additional WHERE clauses.

### 6.2 RLS Policy Example

```sql
ALTER TABLE record ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON record
    USING (tenant_id = current_setting('app.current_tenant')::uuid);
```

## 7. Automation Engine

### 7.1 Execution Lifecycle

```
1. Resolve tenant + permissions (reactive)
2. Load object metadata from cache
3. Type coercion and field validation
4. Execute BEFORE automations (declarative + user-code)
5. Execute validation rules (DSL expressions)
6. Persist record (R2DBC transaction)
7. Publish AFTER events to outbox table (same transaction)
8. [Async] Outbox worker delivers webhooks + side effects
```

### 7.2 Expression DSL

Safe, sandboxed expression evaluator (whitelist approach). Supported operations:
- Comparison: `==`, `!=`, `<`, `>`, `<=`, `>=`
- Boolean: `AND`, `OR`, `NOT`
- Field references: `{field_api_name}`
- Pure functions: `ISBLANK()`, `LEN()`, `CONTAINS()`, `TODAY()`, `NOW()`, `ROUND()`, `FLOOR()`, `CEILING()`

Never evaluates as raw Java/JVM code.

### 7.3 UserCodeExecutor Port

```java
public interface UserCodeExecutor {
    Mono<ExecutionResult> execute(UserCodeDefinition code, ExecutionContext ctx);
}
```

Phase 5 implementation: sandboxed DSL/scripting.
Future: WASM runtime or container-based isolation.

## 8. Spring AI Integration

### 8.1 Use Cases (All Off Critical Path)

1. **NL → Metadata**: User says "create a Project object with name, due date, and assignee" → Spring AI produces a validated metadata proposal → user confirms → metadata engine applies it.
2. **NL → Query**: Natural language question → Spring AI translates to Query Engine DSL → always subject to user permissions.
3. **Contextual assistance**: Help building validation rules, suggesting fields, explaining errors.

### 8.2 Safety

- AI output is always validated against JSON Schema before application.
- AI never receives database credentials or connection parameters.
- AI cannot bypass tenant isolation or FLS/RLS.
- All AI calls are async (`Mono<AIResponse>`), never blocking.

## 9. Infrastructure

See `infrastructure/` directory. Pulumi TypeScript stacks provision:
- PostgreSQL (managed or container, leveraging services from `hneyra/iaac`)
- Container registry
- Application compute (container service)
- Networking (VPC, security groups)
- Secrets management
- Observability stack
