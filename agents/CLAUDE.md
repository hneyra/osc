# Agent Instructions — OSC Business Application Engine

This document is for AI coding agents (Claude Code). Human developer guide is in `agents/PROGRAMMERS.md`.

## Session Start Checklist

Before writing any code:
1. Read `docs/PROJECT.md` — architecture, phases, decisions, glossary.
2. Read `docs/ARCHITECTURE.md` — technical details, reactive patterns, examples.
3. Read relevant ADRs in `docs/adr/` — especially ADR-001, ADR-002, ADR-003.
4. Read the relevant contract in `docs/contracts/` for the current phase.
5. Identify **which phase** we're implementing. Do not build on abstractions that don't exist yet.
6. Confirm the task scope before generating code. Output implementation + tests.

## Non-Negotiable Stack Decisions

These are fixed. Do not propose alternatives or use other technologies:

| What | Technology | Why non-negotiable |
|---|---|---|
| Java version | **Java 25** | Virtual threads, sealed classes, modern APIs |
| Framework | **Spring Boot 4.x + Spring WebFlux** | Reactive end-to-end |
| DB driver | **R2DBC (reactive)** | No blocking I/O, consistent with WebFlux |
| AI | **Spring AI 2 M6** | Specified |
| Build | **Gradle with Kotlin DSL** | Typed, flexible build |
| Infrastructure | **Pulumi TypeScript** (`infrastructure/`) | IaC as code |
| DB | **PostgreSQL 16+** with RLS and JSONB | ACID + flexible schema |
| Migrations | **Flyway** | Versioned, auditable |

**Never use**: Maven, Spring MVC (blocking), JDBC, Spring Boot 3.x, Java < 25, Hibernate ORM.

## Reactive Programming Rules

1. **All database operations** use R2DBC via `DatabaseClient` or R2DBC repositories. Return `Mono<T>` or `Flux<T>`.
2. **Never call `.block()`** or any blocking API on the event loop thread.
3. **Tenant context** must propagate via **Reactor `Context`** — not ThreadLocal (incompatible with reactive).
4. **Errors** are handled reactively: `.onErrorResume()`, `.onErrorMap()`, never try/catch around Mono/Flux chains.
5. **Transactions** via `TransactionalOperator` or `@Transactional` on reactive methods.

```java
// CORRECT — reactive with context
public Mono<Record> findById(UUID id) {
    return Mono.deferContextual(ctx -> {
        String tenantId = ctx.get(TENANT_KEY);
        return databaseClient.sql("SELECT * FROM record WHERE id = $1 AND tenant_id = $2")
            .bind("$1", id)
            .bind("$2", tenantId)
            .map(this::mapRow)
            .one();
    });
}

// WRONG — blocking
public Record findById(UUID id) {
    return databaseClient.sql("...").map(this::mapRow).one().block(); // NEVER
}
```

## Multi-tenancy Rules

- Every table has `tenant_id NOT NULL`.
- Every query filters by `tenant_id` explicitly in the SQL — even with RLS enabled.
- `tenant_id` comes from Reactor Context only (never from user input or path parameters).
- RLS must be enabled on every data table in Flyway migrations.
- Tests MUST include cross-tenant isolation tests (trying to access another tenant's data must return empty/forbidden).

## SQL Safety Rules

- All SQL uses **R2DBC parameterized bindings** (`$1`, `$2`, etc.) — never string concatenation or interpolation.
- Query Engine validates every field/object name against metadata before including it in SQL.
- Tests MUST include SQL injection attempts that must return empty or error, never data.

```java
// CORRECT
databaseClient.sql("SELECT * FROM record WHERE tenant_id = $1 AND id = $2")
    .bind("$1", tenantId)
    .bind("$2", recordId)

// WRONG — SQL injection vector
databaseClient.sql("SELECT * FROM record WHERE name = '" + userInput + "'")
```

## Code Style

- All code and identifiers in **English**.
- No Lombok. Use Java records for DTOs/value objects.
- Sealed interfaces for discriminated unions (field types, automation kinds, etc.).
- One class per file. Package by module/feature, not by type.
- No `@SuppressWarnings` without a comment explaining why.
- No commented-out code committed.

## Testing Standards

Every task must deliver tests. "Done" requires:
- Unit tests for business logic (MockR2DBC or H2 R2DBC for repositories).
- Integration tests for the reactive pipeline (TestContainers + real PostgreSQL).
- Tenant isolation test: verify cross-tenant data access returns empty.
- Security test: verify SQL injection vectors are rejected.

```java
@Test
void crossTenantIsolation_returnsEmpty() {
    // Given: record created for tenant A
    // When: queried with tenant B context
    // Then: result is empty (not forbidden, just empty — no information leak)
    StepVerifier.create(recordService.findById(recordId).contextWrite(tenantBContext))
        .verifyComplete(); // empty, not error
}
```

## Metadata Engine Usage

Never load metadata directly from DB in business logic. Always go through `MetadataEngine`:

```java
// CORRECT
metadataEngine.findObject(tenantId, "Project__c")
    .flatMap(objectDef -> persistenceLayer.insert(objectDef, fieldValues))

// WRONG — bypassing cache
r2dbcClient.sql("SELECT * FROM md_object WHERE ...").fetch()...
```

## Flyway Migration Rules

- Migrations in `backend/persistence/src/main/resources/db/migration/`.
- Naming: `V{version}__{description}.sql` — e.g., `V1__initial_metadata_schema.sql`.
- Never edit or delete an already-applied migration.
- New changes always get a new version.
- Every new table must have `tenant_id`, RLS policy, and appropriate indexes.

## ADR Requirements

When making a non-trivial architectural decision not already covered by an existing ADR:
1. Stop and document it in `docs/adr/ADR-XXX-title.md` before implementing.
2. Follow the ADR template in `docs/adr/`.
3. Reference the ADR in the commit message.

## Definition of Done

A task is done when ALL of the following are true:
- [ ] Tests pass (unit + integration + security/isolation)
- [ ] No `.block()` calls in production code
- [ ] No String SQL concatenation
- [ ] Every query includes explicit `tenant_id` filter
- [ ] RLS enabled on any new table (via Flyway migration)
- [ ] New architectural decisions documented as ADR
- [ ] `docs/PROJECT.md` updated if a decision changed
