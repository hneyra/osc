# OSC — Business Application Engine: Architecture Overview

> Full technical details → [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
> Master plan and phases → [docs/PROJECT.md](docs/PROJECT.md)

## Core Concept

A runtime-configurable, multi-tenant PaaS/SaaS engine. Almost everything is **interpreted metadata at runtime** — when a user creates an object, field, or rule, metadata records are written and the engine reads them dynamically. No redeployment required.

## Non-Negotiable Stack

| Layer | Technology |
|---|---|
| Runtime | Java 25 |
| Framework | Spring Boot 4.x + Spring WebFlux (reactive) |
| Database access | R2DBC (reactive PostgreSQL driver) |
| AI | Spring AI 2 M6 |
| Build | Gradle (Kotlin DSL) |
| Database | PostgreSQL 16+ with RLS, JSONB, GIN indexes |
| Migrations | Flyway |
| Cache | Caffeine (in-process) → Redis (horizontal scale) |
| Frontend | React + TypeScript + Vite |
| Infrastructure | Pulumi TypeScript (`infrastructure/`) |

## Two-Plane Model

1. **Metadata Plane** — what can exist (objects, fields, rules, layouts, permissions). Relatively stable, cacheable.
2. **Data Plane** — actual records created by users. Grows unbounded; its shape depends on the metadata.

## Architecture Layers

```
┌──────────────────────────────────────────────────────────────┐
│  Dynamic REST API (auto-generated from metadata per tenant)   │
├──────────────────────────────────────────────────────────────┤
│  Security / Permissions Layer (tenant, object, field, record) │
├──────────────────────────────────────────────────────────────┤
│  Validation & Automation Engine (rules, flows, user-code)     │
├──────────────────────────────────────────────────────────────┤
│  Query Engine (SOQL-like → parameterized R2DBC SQL)           │
├──────────────────────────────────────────────────────────────┤
│  Dynamic Persistence Layer (JSONB + promoted columns, R2DBC)  │
├──────────────────────────────────────────────────────────────┤
│  Metadata Engine (reactive load + Caffeine cache + invalidation)│
├──────────────────────────────────────────────────────────────┤
│  PostgreSQL 16+ (metadata tables + record table, RLS/tenant)  │
└──────────────────────────────────────────────────────────────┘
                          ↕
              ┌───────────────────────┐
              │  Spring AI 2 M6 Layer │  (transversal, off critical path)
              │  NL→metadata, NL→query│
              └───────────────────────┘
```

**AI principle:** AI is never on the critical path of a data operation. The engine is deterministic; AI is a productivity layer on top.

## Multi-tenancy

Shared-schema + PostgreSQL RLS. Every table has `tenant_id NOT NULL`. The application sets `app.current_tenant` per transaction via `SET LOCAL`, derived from the JWT claim. Defense in depth: RLS at DB level + explicit filter at application level.

## Reactive End-to-End

All I/O is non-blocking: R2DBC for database, WebClient for HTTP, reactive event publishing. No `.block()` calls on the event loop thread. Every service returns `Mono<T>` or `Flux<T>`.

## Execution Phases

| Phase | Focus | Priority |
|---|---|---|
| 0 | Metadata model foundations | Critical |
| 1 | Dynamic persistence layer (R2DBC + JSONB) | Critical |
| 2 | Query engine + dynamic REST API | Critical |
| 3 | Frontend renderer + design system | High |
| 4 | Security & permissions (FLS + RLS) | Critical |
| 5 | Automation engine (DSL + user-code sandbox) | High |
| 6 | Integrations + AI layer (Spring AI) | High |
| 7 | Hardening, observability, multi-team | High |
