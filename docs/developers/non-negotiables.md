# OSC — Non-Negotiables

These constraints are **architectural laws**. They cannot be overridden by any team member, sprint priority, or deadline. Each one has a documented rationale. Violating them is a blocker for merge.

---

## Runtime & Framework

### NNG-001 · Java 25 only
**Rule:** All backend code compiles and runs on Java 25. No code targeting Java < 25.  
**Why:** Virtual threads, pattern matching, sealed classes, and record patterns are core language features used throughout.  
**Verify:** `./gradlew compileJava` with the Java 25 toolchain resolves without error.

### NNG-002 · Spring Boot 4.x + Spring WebFlux only
**Rule:** No Spring MVC, no blocking servlet stack. Every HTTP handler returns `Mono<T>` or `Flux<T>`.  
**Why:** Non-blocking I/O is required for the concurrency model. Spring Boot 3.x is not supported.  
**Verify:** ArchUnit rule `NoBlockingOperationsInReactiveRule` must pass.

### NNG-003 · R2DBC for all database access
**Rule:** No JDBC, no JPA, no Hibernate, no `JdbcTemplate`. All DB calls go through R2DBC.  
**Why:** JDBC blocks the event loop thread, destroying throughput.  
**Verify:** No import of `java.sql.*` or `javax.sql.*` in production code.

### NNG-004 · No `.block()` in production code
**Rule:** `.block()`, `.blockFirst()`, and `.blockLast()` are forbidden in any non-test code path.  
**Why:** Blocking inside a reactive pipeline stalls the event loop thread, causing cascading failures.  
**Detect:** ArchUnit rule `NoBlockCallsRule` fails the build if a `.block()` call is found in `src/main`.

**Scoped exception (ADR-005):** `backend/kotlin-scripting` may call `.block()`, but only inside classes scheduled on `Schedulers.boundedElastic()` (the `ExecutionContext`/`RecordOperations` facades exposed to Kotlin scripts). Kotlin script compilation and evaluation are inherently blocking JVM operations; isolating that behind the elastic scheduler with a hard timeout is the same pattern this rule already prescribes for "CPU-bound or legacy-blocking code." This exception is enforced and bounded by its own rule, `KotlinScriptingBlockingIsolationRule`, scoped to that module only — `NoBlockCallsRule` still applies unmodified to every other module.

---

## Multi-Tenancy

### NNG-005 · Every table carries `tenant_id NOT NULL`
**Rule:** All Flyway migrations that create a table MUST include `tenant_id UUID NOT NULL REFERENCES tenant(id)`.  
**Why:** Without it, data can leak across tenants at query time.  
**Verify:** `FlywayMigrationV*Test` checks schema integrity.

### NNG-006 · Every query filters by `tenant_id` explicitly
**Rule:** Every R2DBC query against any multi-tenant table must include `WHERE tenant_id = :tenantId` as a bind parameter. Relying on RLS alone is not acceptable.  
**Why:** Defense-in-depth. RLS is the safety net, application filtering is the primary guard.  
**Verify:** Integration tests in each module include `TenantIsolationIntegrationTest`.

### NNG-007 · `tenant_id` comes from Reactor Context only
**Rule:** `tenant_id` is extracted from the validated JWT in `TenantContextFilter` and placed in Reactor `Context`. Services read it from `Context`, never from method arguments, ThreadLocal, request bodies, or query parameters.  
**Why:** Prevents tenant spoofing.  
**Detect:** Code review + ArchUnit rule prohibiting `ThreadLocal` access in reactive chains.

### NNG-008 · RLS must be enabled on all data tables
**Rule:** Every Flyway migration creating a table must run `ALTER TABLE … ENABLE ROW LEVEL SECURITY` and define the corresponding policy `USING (tenant_id = current_setting('app.current_tenant')::uuid)`.  
**Why:** DB-level enforcement as a second line of defense.  
**Verify:** `FlywayMigrationV1Test` asserts RLS policies exist on each table.

---

## SQL Safety

### NNG-009 · Parameterized SQL always — never string concatenation
**Rule:** No `"SELECT … WHERE name = '" + value + "'"` anywhere. R2DBC `.bind()` parameters always.  
**Why:** SQL injection. Parameterization is the only reliable defense.  
**Verify:** `SqlInjectionPreventionTest` sends injection payloads and asserts they are safely handled.

### NNG-010 · QueryEngine validates field and object names
**Rule:** The `query-engine` module must validate that every field name and object name in a query corresponds to an entry in `MetadataEngine` for the current tenant before translating to SQL.  
**Why:** Field names appear in the SQL column path (`data->>'fieldName'`) — they must be validated, not interpolated.  
**Verify:** `QueryEngineInjectionTest`.

---

## Reactive Correctness

### NNG-011 · Tenant context via Reactor Context, not ThreadLocal
**Rule:** All cross-cutting state (tenant, user, correlation ID) travels through `reactor.util.context.Context`, not `ThreadLocal`.  
**Why:** Virtual threads and reactive scheduling break ThreadLocal semantics.  
**Implementation:** See `TenantContextFilter` and `SecurityContext` for the canonical pattern.

### NNG-012 · Transactions via `TransactionalOperator` or `@Transactional` (R2DBC)
**Rule:** Database transactions are managed via `TransactionalOperator.transactional(…)` or `@Transactional` on reactive service methods (Spring R2DBC transaction management). No manual `BEGIN/COMMIT` strings.  
**Why:** Ensures proper transaction propagation in reactive pipelines.

### NNG-013 · Error handling must be reactive
**Rule:** Use `.onErrorResume()`, `.onErrorMap()`, `switchIfEmpty()`. Do not wrap reactive code in try/catch unless catching from a non-reactive boundary.  
**Why:** Exceptions thrown inside a reactive chain without proper handling silently drop.

---

## Metadata & Contracts

### NNG-014 · Contracts before implementation
**Rule:** Any non-trivial decision (new module, architectural change, external dependency) requires an ADR in `docs/adr/` before the PR is opened.  
**Why:** Prevents silent architectural drift.  
**Format:** Follow the template in `docs/adr/ADR-001-multi-tenancy.md`.

### NNG-015 · JSON Schema contracts must be maintained
**Rule:** Any change to the metadata model (new field type, new layout property, new validation DSL) must update the corresponding JSON Schema in `docs/contracts/`.  
**Why:** The frontend, AI module, and integrations all consume these schemas.

### NNG-016 · Flyway migrations are immutable once applied
**Rule:** Never modify a `V{n}__*.sql` migration that has already been applied to any environment. Add a new migration version instead.  
**Why:** Flyway checksum validation will fail in all existing environments, breaking deployments.

---

## AI Module

### NNG-017 · AI is never on the critical data path
**Rule:** No record creation, update, or deletion may be triggered directly by AI output. AI output must be validated against JSON Schema and presented to the user for confirmation.  
**Why:** LLM outputs are probabilistic. Direct writes would be unpredictable and potentially destructive.  
**Implementation:** `NlToMetadataService` and `NlToQueryService` return proposals only.

### NNG-018 · Spring AI 2 M6 only
**Rule:** No other AI SDK or direct LLM HTTP client. All AI calls go through `spring-ai-*` dependencies at version 2.0.0-M6.  
**Why:** Consistency, testability, and the project's chosen abstraction layer.

---

## User Code & Scripting (ADR-005)

### NNG-023 · A script cannot be activated with a failing compile
**Rule:** `md_script.is_active` can only transition to `true` when `compile_errors` is empty. The compiler runs on save, not on execution.
**Why:** Prevents a runtime compile failure from breaking a live trigger pipeline.
**Verify:** DB `CHECK` constraint (`NOT is_active OR compile_errors = '[]'::jsonb`) plus `ScriptActivationServiceTest`.

### NNG-024 · No elevated permissions for script execution
**Rule:** Every Kotlin script executes with the invoking user's `SecurityContext`. FLS/RLS apply to every `RecordOperations` call exactly as they do on the REST API path.
**Why:** A scripting engine that bypasses permission checks would defeat the entire security model (§ADR-001, §security-model.md).
**Verify:** `ScriptPermissionEnforcementTest` — a script run as a low-privilege user cannot read/write fields that user lacks FLS access to.

### NNG-025 · AI-generated scripts never auto-activate
**Rule:** A script proposed by the AI layer is persisted with `is_active = false` and `generated_by_ai = true`. Only an explicit human action can set `is_active = true`.
**Why:** Consistent with NNG-017 — AI output never mutates live behavior without human confirmation.
**Verify:** `AiScriptProposalServiceTest`.

## Extended Metadata (ADR-006)

### NNG-026 · Master-Detail cascade delete is transactional, not a DB-level FK
**Rule:** Because all records share the universal `record` table, Master-Detail cascade delete is enforced in `DynamicPersistenceService` inside the same R2DBC transaction as the parent delete — there is no physical per-object foreign key to attach `ON DELETE CASCADE` to.
**Why:** Preserves ADR-002's "everything lives in `record`" model while still guaranteeing cascade integrity.
**Verify:** `MasterDetailCascadeIntegrationTest`.

### NNG-027 · Rollup fields are never computed synchronously
**Rule:** `ROLLUP` field recomputation happens only via the outbox/AFTER pipeline, triggered by a child-record write. It must never block or extend the latency of the child record's own transaction.
**Why:** Avoids fan-out latency on every child write; rollups are explicitly eventually-consistent, documented as such in the public API contract.
**Verify:** `RollupRecalculationIntegrationTest` asserts the child write's response time is unaffected by rollup recomputation.

## Build & Infrastructure

### NNG-019 · Gradle Kotlin DSL only
**Rule:** No `pom.xml`, no Groovy `build.gradle`. All build files are `.gradle.kts`.  
**Why:** Type safety, IDE support, and project standard.

### NNG-020 · Infrastructure via Pulumi TypeScript only
**Rule:** No CloudFormation, no Terraform, no manual console changes to shared infrastructure.  
**Why:** Reproducible, audited, code-reviewed infrastructure state.  
**Location:** `infrastructure/` — changes require PR like any other code.

---

## Testing

### NNG-021 · Tests are part of "done" — not optional
**Rule:** Every PR that touches production code must include:
- At least one unit test for new logic
- At least one integration test using TestContainers (real PostgreSQL)
- A tenant isolation test if any new query is introduced
- A SQL injection test if any new user-controlled input reaches a query

**Why:** Without automated tests, tenant isolation and SQL safety regressions are undetectable.

### NNG-022 · ArchUnit rules must pass
**Rule:** The ArchUnit test suite (`osc.java-conventions`) runs on every `./gradlew test`. It enforces:
- No blocking calls in reactive code
- No JDBC imports in production code
- Proper layer dependencies (api → security → automation → query-engine → persistence → metadata-engine)

---

## Summary Checklist

Before merging any PR, verify:

- [ ] No `.block()` in `src/main`
- [ ] No string-concatenated SQL
- [ ] Every new table has `tenant_id NOT NULL` + RLS policy
- [ ] Every new query has explicit `tenant_id = :tenantId` bind
- [ ] Tenant isolation test exists for new queries
- [ ] Flyway migrations are new versions, not edits of existing ones
- [ ] ADR created if the PR introduces a non-trivial architectural decision
- [ ] JSON Schema contracts updated if metadata model changed
- [ ] ArchUnit passes
- [ ] Tests are green including TestContainers integration tests
