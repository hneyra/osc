# CLAUDE.md

Este repositorio implementa un **motor de aplicaciones de negocio configurable en runtime** (PaaS/SaaS multi-tenant).

La fuente de verdad de arquitectura y plan de ejecución es **[docs/PROJECT.md](docs/PROJECT.md)**.

## Antes de empezar cualquier tarea

1. Lee `docs/PROJECT.md` — arquitectura, fases, decisiones.
2. Lee los ADR relevantes en `docs/adr/`.
3. Lee el contrato relevante en `docs/contracts/`.
4. Identifica en qué FASE estamos; no construyas sobre lo que aún no existe.
5. Lee `agents/CLAUDE.md` para instrucciones detalladas de agente IA.

## Stack no negociable

| Capa | Tecnología |
|---|---|
| Runtime | Java 25 |
| Framework | Spring Boot 4.x + Spring WebFlux (reactivo) |
| DB access | R2DBC (driver PostgreSQL reactivo) |
| IA | Spring AI 2 M6 |
| Build | Gradle (Kotlin DSL) |
| Base de datos | PostgreSQL 16+ (RLS, JSONB, GIN) |
| Migraciones | Flyway |
| Caché | Caffeine → Redis (escalado horizontal) |
| Frontend | React + TypeScript + Vite |
| Infra | Pulumi TypeScript (`infrastructure/`) |

**No usar**: Maven, Spring MVC blocking, JDBC síncrono, Spring Boot 3.x, Java < 25.

## Principios no negociables

- **Multi-tenant**: toda tabla y query lleva `tenant_id`; RLS activo + filtro de aplicación.
- **Seguridad**: SQL siempre parametrizado (R2DBC binds); tests de inyección y aislamiento de tenant son parte de "hecho".
- **La IA propone, el motor dispone**: ninguna salida de IA modifica el sistema sin validación de esquema y confirmación.
- **User-code** corre siempre con permisos del usuario y límites de recursos.
- **Contratos antes que implementación**. Cada decisión no trivial → un ADR en `docs/adr/`.
- **Reactive end-to-end**: sin operaciones bloqueantes en el thread del event loop.

## Convenciones

- Código y nombres en **inglés**.
- Backend: módulos en `backend/<módulo>/`, cada uno con su `build.gradle.kts`.
- Frontend: componentes en `frontend/design-system/` y `frontend/renderer/`.
- Migraciones Flyway en `backend/persistence/src/main/resources/db/migration/`, versionadas, nunca editar una ya aplicada.
- Tests: cada módulo entrega tests; nada se mergea sin ellos.
- Reactive: usar `Mono<T>` y `Flux<T>` de Project Reactor; nunca `.block()` en producción.

## Definición de "hecho"

Tests verdes (incluyendo seguridad/aislamiento) + demo manual + `docs/PROJECT.md`/ADR actualizados si cambió una decisión.

## Estructura de referencia

```
docs/PROJECT.md         — Fuente de verdad (arquitectura + fases)
docs/ARCHITECTURE.md    — Detalles técnicos de arquitectura
docs/adr/               — Architecture Decision Records
docs/contracts/         — Esquemas de metadata, OpenAPI
agents/CLAUDE.md        — Instrucciones detalladas para agentes IA
agents/PROGRAMMERS.md   — Guía para desarrolladores humanos
infrastructure/         — Pulumi TypeScript (infra como código)
backend/                — Módulos del backend (Gradle multi-project)
frontend/               — React + TypeScript
```
