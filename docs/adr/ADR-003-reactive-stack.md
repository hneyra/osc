# ADR-003: Reactive Stack — Spring WebFlux + R2DBC

**Status:** Accepted
**Date:** 2026-05-24
**Deciders:** Project Lead

## Context

The system needs to handle concurrent requests from multiple tenants efficiently. Options:
1. Spring MVC (thread-per-request, blocking) + JDBC
2. Spring WebFlux (reactive, non-blocking) + R2DBC

The project also specifies Java 25, which includes virtual threads (Project Loom). However, the explicit requirement is Spring Reactive.

## Decision

**Spring WebFlux + R2DBC** for the entire backend. Java 25 for virtual threads on CPU-bound tasks.

- All HTTP handlers: `Mono<ResponseEntity<T>>` or `ServerSentEvents`.
- All DB access: R2DBC `DatabaseClient` or `R2dbcRepository`.
- Tenant context: Reactor `Context` (not ThreadLocal — incompatible with reactive).
- CPU-bound or legacy-blocking code: `Schedulers.boundedElastic()`.

## Consequences

**Good:**
- Non-blocking I/O: efficient under high concurrency, few threads needed.
- Consistent programming model end-to-end.
- Java 25 virtual threads available for elastic scheduler offloading.
- Spring Boot 4.x aligns with WebFlux first-class support.

**Bad / Risks:**
- Steeper learning curve than Spring MVC.
- Debugging reactive chains is harder (stack traces less readable).
- `.block()` calls from developers unfamiliar with reactive can cause deadlocks — enforced by linting rule.
- TestContainers + reactive requires careful async test setup.

## Constraints

- No `.block()` in production code. Enforced by ArchUnit test.
- No JDBC or JPA dependencies in production modules.
- Flyway runs synchronously at startup (before the reactive context starts) — this is intentional and acceptable.
- All reactive chains must handle `EmptyResultDataAccessException` via `.switchIfEmpty()` or `.defaultIfEmpty()`.
