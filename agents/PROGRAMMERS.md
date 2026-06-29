# Developer Guide — OSC Business Application Engine

This guide is for human developers joining the project. AI agent instructions are in `agents/CLAUDE.md`.

## Project Overview

OSC is a **runtime-configurable, multi-tenant PaaS/SaaS business application engine**. Think Salesforce's metadata-driven architecture, but built from scratch with a modern reactive Java stack.

**Core idea:** Almost everything is metadata interpreted at runtime. When a user creates an object, field, or rule, metadata records are written — the engine reads them dynamically. No redeployment needed.

Full architecture: `docs/ARCHITECTURE.md`. Master plan: `docs/PROJECT.md`.

## Getting Started

### Prerequisites

- Java 25 (use SDKMAN: `sdk install java 25-graalce` or similar)
- Gradle 8.x (wrapper included: `./gradlew`)
- Docker + Docker Compose (for local PostgreSQL)
- Node.js 20+ (for frontend and Pulumi)
- Pulumi CLI (for infrastructure)

### Local Setup

```bash
# Clone and enter
git clone https://github.com/hneyra/osc
cd osc

# Start local PostgreSQL
docker compose up -d postgres

# Run backend (all modules)
./gradlew :backend:api:bootRun

# Run frontend (from frontend/runtime)
cd frontend/runtime && npm install && npm run dev

# Run all tests
./gradlew test
```

### Environment Variables

| Variable | Description | Default |
|---|---|---|
| `DB_URL` | R2DBC PostgreSQL URL | `r2dbc:postgresql://localhost:5432/osc` |
| `DB_USERNAME` | Database user | `osc` |
| `DB_PASSWORD` | Database password | (from secrets) |
| `JWT_SECRET` | JWT signing secret | (from secrets) |
| `SPRING_AI_OPENAI_API_KEY` | AI provider key | (from secrets) |

## Project Structure

```
osc/
├── CLAUDE.md                    # AI agent entry point
├── ARCHITECTURE.md              # Architecture overview
├── docs/
│   ├── PROJECT.md               # Master plan (READ THIS FIRST)
│   ├── ARCHITECTURE.md          # Technical details
│   ├── adr/                     # Architecture Decision Records
│   └── contracts/               # JSON Schema + OpenAPI contracts
├── agents/
│   ├── CLAUDE.md                # AI agent detailed instructions
│   └── PROGRAMMERS.md           # This file
├── backend/                     # Gradle multi-project
│   ├── settings.gradle.kts
│   ├── metadata-engine/         # Metadata load, cache, model
│   ├── persistence/             # Dynamic persistence (R2DBC + JSONB)
│   ├── query-engine/            # SOQL-like → parameterized SQL
│   ├── automation/              # Validation rules, flows, user-code
│   ├── security/                # Tenant, permissions, RLS glue
│   ├── kotlin-scripting/        # Kotlin Scripting compiler, sandbox, execution (ADR-005)
│   ├── api/                     # Dynamic REST API
│   ├── ai/                      # Spring AI: NL→metadata, NL→query, NL→script proposal
│   └── integrations/            # Webhooks, outbox, outbound clients
├── frontend/
│   ├── design-system/           # Base components (~30)
│   ├── renderer/                # LayoutRenderer, FieldRenderer, ListViewRenderer
│   ├── script-editor/           # Kotlin script editor UI (ADR-005)
│   ├── admin/                   # Configuration UI
│   └── runtime/                 # App consuming metadata + data
└── infrastructure/              # Pulumi TypeScript
```

## Architecture Principles

### 1. Reactive End-to-End

We use **Spring WebFlux + R2DBC** — fully non-blocking, reactive I/O. All service methods return `Mono<T>` or `Flux<T>`. If you need to call blocking code (legacy library, CPU-bound), use `Schedulers.boundedElastic()`.

### 2. Multi-tenancy is Sacred

- Every table has `tenant_id NOT NULL`.
- Every query explicitly filters by `tenant_id` — both at the app level AND PostgreSQL RLS enforces it.
- The `tenant_id` comes only from the JWT claim, never from the URL or request body.
- Violation of this principle is a **security bug**, treated as P0.

### 3. Metadata Drives Everything

Don't hardcode business objects. If you're writing code specific to "Account" or "Contact" — stop. That logic belongs in metadata. The engine interprets metadata at runtime.

### 4. SQL Safety

All SQL uses parameterized bindings. String concatenation in SQL is forbidden and will be rejected in code review.

### 5. Contracts Before Code

Before implementing, check if there's a contract in `docs/contracts/`. If not, write the contract (JSON Schema, OpenAPI) first, get it reviewed, then implement.

## Development Workflow

### Branch Naming

```
feature/phase-N-short-description    # e.g., feature/phase-1-record-crud
fix/short-description                 # e.g., fix/tenant-isolation-leak
chore/short-description               # e.g., chore/update-flyway-migration
```

### Commit Messages

Follow Conventional Commits:
```
feat(persistence): add reactive CRUD for records
fix(query-engine): prevent tenant_id bypass in WHERE clause
test(security): add cross-tenant isolation test suite
docs(adr): add ADR-003 reactive stack decision
```

### Pull Request Requirements

- [ ] All tests pass (`./gradlew test`)
- [ ] No `.block()` in production code (linting enforced)
- [ ] No SQL string concatenation
- [ ] Every query filters by `tenant_id`
- [ ] New tables have RLS policy in Flyway migration
- [ ] Architectural decisions documented as ADR if applicable

### Code Review Focus Areas

1. **Tenant isolation** — does every DB query have the `tenant_id` filter?
2. **No blocking** — any `.block()` or blocking I/O outside elastic scheduler?
3. **SQL safety** — parameterized bindings throughout?
4. **Error handling** — reactive error operators used correctly?
5. **Test coverage** — security and isolation tests included?

## Module Responsibilities

### `metadata-engine`

Owns: `ObjectDefinition`, `FieldDefinition`, `MetadataEngine` interface, Caffeine cache.
- Exposes: `Mono<ObjectDefinition> findObject(UUID tenantId, String apiName)`, `Flux<FieldDefinition> findFields(UUID tenantId, UUID objectId)`
- Never exposed directly to API — always mediated by other modules.

### `persistence`

Owns: Flyway migrations, `RecordRepository`, `DynamicPersistenceService`.
- Exposes: `Mono<Record> insert(ObjectDefinition, Map<String, Object>)`, `Mono<Record> findById(UUID)`, etc.
- Handles: JSONB ↔ typed field conversion, `data` column read/write.

### `query-engine`

Owns: `QueryParser`, `QueryTranslator`, `QueryExecutor`.
- Exposes: `Flux<Map<String, Object>> execute(String soqlQuery, SecurityContext)`.
- Enforces: tenant filter injection, FLS stripping, parameterized bindings.

### `security`

Owns: `TenantContextFilter`, `SecurityContextResolver`, `PermissionChecker`.
- Provides: `TenantContext` in Reactor Context, FLS/RLS enforcement.

### `api`

Owns: `RecordController`, `MetadataController`, dynamic routing.
- Depends on: all other modules. Entry point for HTTP requests.

### `automation`

Owns: `ExpressionEvaluator`, `AutomationRunner`, `UserCodeExecutor` port.
- No direct DB access — communicates via other modules.

### `kotlin-scripting`

Owns: `KotlinScriptCompilerService`, `CompiledScriptCache`, `ScriptSandbox`, `ExecutionContext`/`RecordOperations`.
- Compiles on save, caches the compiled script, executes it inside a restricted classloader with a runtime guard.
- The **only** module allowed to call `.block()`, and only inside classes scheduled on `Schedulers.boundedElastic()`. See ADR-005 and `docs/developers/non-negotiables.md` NNG-004.

### `ai`

Owns: `NLToMetadataService`, `NLToQueryService`, `NLToScriptService` (proposes Kotlin script source, compile-checked only — never executed).
- All output validated before applying to the system.

### `integrations`

Owns: `OutboxWorker`, `WebhookDeliveryService`, `OutboundHttpClient`.
- Outbox pattern: events written to DB in same transaction, delivered async.

## Testing

### Test Layers

1. **Unit tests** — pure business logic, no DB. Use `StepVerifier` for reactive code.
2. **Integration tests** — real PostgreSQL via TestContainers. Test the full reactive pipeline.
3. **Security tests** — cross-tenant isolation, SQL injection, permission bypass attempts.

### SQL Injection Test Suite

The golden rule: **all SQL goes through R2DBC parameterized binds (`$1`, `$2`, …) — never string
concatenation.** This is enforced and verified, not just asserted by convention.

Two test suites cover it, one per attack surface:

| Surface | Where | What it proves |
|---|---|---|
| Record **id** parameter | `persistence/SqlInjectionPreventionTest` | a non-UUID id (`' OR '1'='1`, `'; DROP TABLE record; --`) fails on `UUID.fromString` *before* any SQL runs |
| Field **values** | `persistence/SqlInjectionPreventionTest` | malicious values are carried as binds and stored as plain data — the `record` table survives intact |
| Object **api_name** | `persistence/SqlInjectionPreventionTest` | a malicious api_name is only a metadata lookup key; an unknown object fails with `ObjectNotFoundException` and never reaches record SQL |
| SOQL-like queries (api_name / field / value) | `query-engine/QueryEngineInjectionTest` | every name is validated against metadata and every value is a bind; injection vectors yield empty/validation error |

The suite also runs **ArchUnit** rules asserting repository classes never call `String.format` or use
`StringBuilder` to assemble SQL. Each literal vector from issue #21 (including the `pg_sleep` timing
attack) appears in one of these tests. Rule: every vector must return empty or a validation error —
**never data, never an unexpected exception.**

### Running Tests

```bash
# All tests
./gradlew test

# Specific module
./gradlew :backend:query-engine:test

# With TestContainers (needs Docker)
./gradlew :backend:persistence:test
```

### TestContainers Setup

```java
@SpringBootTest
@Testcontainers
class RecordRepositoryTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
        .withInitScript("db/init.sql");

    // R2DBC connection configured to TestContainers URL
}
```

## Infrastructure

See `infrastructure/` for Pulumi TypeScript stacks. Existing shared services from `hneyra/iaac` are imported and referenced — don't re-provision what already exists.

### Local Infrastructure

```bash
cd infrastructure
npm install
pulumi stack select dev
pulumi preview  # dry run
pulumi up       # apply
```

## FAQ

**Q: Why R2DBC and not JPA/Hibernate?**
A: Spring WebFlux is non-blocking. JPA/Hibernate are blocking by design. R2DBC is the reactive alternative. See ADR-003.

**Q: Why not dynamic DDL for custom fields?**
A: Dynamic `ALTER TABLE` in a multi-tenant environment creates lock contention, migration complexity, and operational risk. JSONB gives flexibility without DDL. See ADR-002.

**Q: Why Gradle Kotlin DSL instead of Maven?**
A: Typed configuration, IDE autocompletion, better composability for a multi-module build. Explicitly required by the project.

**Q: Can I add a new dependency?**
A: For production dependencies, open a discussion first. For test dependencies, add to the relevant module's `build.gradle.kts`.

**Q: Why does `kotlin-scripting` get to call `.block()` when nothing else can?**
A: Kotlin script compilation/execution are inherently blocking JVM operations (same category as any CPU-bound or legacy-blocking work). Isolating that behind `Schedulers.boundedElastic()` with a hard timeout is the same pattern NNG-004 already prescribes elsewhere — it's just given its own ArchUnit rule (`KotlinScriptingBlockingIsolationRule`) scoped to that module instead of a case-by-case exception. See ADR-005.

**Q: I want to build a new standard module (e.g., Invoicing, Inventory). Where do I start?**
A: See `docs/developers/new-module-guide.md` — it walks through defining the object/field metadata, adding relationships and record types, wiring layouts, and where Kotlin Scripting triggers fit in, without writing any bespoke Java/Kotlin business-object code.
