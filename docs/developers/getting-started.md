# Getting Started — OSC Development

## Prerequisites

| Tool | Version | Install |
|---|---|---|
| Java | 25 | [sdkman.io](https://sdkman.io) · `sdk install java 25-graal` |
| Gradle | 8.x | Included via `./gradlew` wrapper |
| Docker | 24+ | [docker.com](https://docs.docker.com/get-docker/) |
| Node.js | 20+ | [nodejs.org](https://nodejs.org) or `nvm install 20` |
| Pulumi CLI | 3.x | `curl -fsSL https://get.pulumi.com | sh` |

## Local Setup

### 1. Start infrastructure dependencies

```bash
docker compose up -d postgres
```

This starts PostgreSQL 16 on `localhost:5432`. Flyway migrations run automatically on app startup.

### 2. Run the backend

```bash
./gradlew :backend:api:bootRun
```

The API starts at `http://localhost:8080`. OpenAPI/Swagger UI at `http://localhost:8080/swagger-ui.html`.

### 3. Run the frontend

```bash
cd frontend/renderer
npm install
npm run dev
```

Frontend starts at `http://localhost:5173`. It proxies API calls to `localhost:8080`.

### 4. Verify everything works

```bash
# Health check
curl http://localhost:8080/actuator/health

# List seeded objects (requires Authorization header — see below)
curl -H "Authorization: Bearer <jwt>" http://localhost:8080/api/v1/Account/records
```

## Environment Variables

| Variable | Required | Default | Description |
|---|---|---|---|
| `DB_URL` | Yes | `r2dbc:postgresql://localhost:5432/osc` | R2DBC connection URL |
| `DB_USER` | Yes | `osc` | Database username |
| `DB_PASSWORD` | Yes | `osc` | Database password |
| `JWT_SECRET` | Yes | `dev-secret` | HMAC key for JWT validation |
| `SPRING_AI_ANTHROPIC_API_KEY` | No | — | Required only for AI features |
| `RATE_LIMIT_RPS` | No | `100` | Requests per second per tenant |
| `CACHE_TTL_SECONDS` | No | `300` | Metadata cache TTL |

Set in a `.env` file at the project root (ignored by git):

```bash
# .env (example)
DB_URL=r2dbc:postgresql://localhost:5432/osc
DB_USER=osc
DB_PASSWORD=osc
JWT_SECRET=dev-secret-change-in-prod
```

## Running Tests

```bash
# All modules
./gradlew test

# Single module
./gradlew :backend:persistence:test

# With coverage report
./gradlew test jacocoTestReport
```

Integration tests use **TestContainers** — they spin up a real PostgreSQL container automatically. Docker must be running.

## Development Workflow

### Branch naming

```
feature/phase-N-short-description   # new feature
fix/short-description                # bug fix
chore/short-description              # tooling, deps, docs
```

### Commit messages — Conventional Commits

```
feat(persistence): add record soft-delete with cascade
fix(query-engine): handle null literal in WHERE clause
chore(deps): upgrade spring-boot to 4.0.1
test(security): add cross-tenant FLS isolation test
docs(adr): add ADR-005 for rate limiting strategy
```

### Pull Request requirements

Before opening a PR:

1. `./gradlew test` passes (all modules, including ArchUnit)
2. No `.block()` introduced (ArchUnit enforces this)
3. No string-concatenated SQL
4. New tables have `tenant_id NOT NULL` + RLS policy
5. New queries have explicit `tenant_id` bind
6. Tenant isolation test exists if a new query was added
7. ADR created if an architectural decision was made
8. `docs/PROJECT.md` updated if phase status or decisions changed

### Code review focus areas

- Tenant isolation: does every query filter by tenant?
- No blocking: no `.block()`, no JDBC, no Thread.sleep()
- SQL safety: all user-controlled input goes through bind parameters
- Error handling: reactive error paths covered
- Tests: isolation test, injection test, unit + integration

## Project Structure at a Glance

```
osc/
├── backend/
│   ├── api/              ← Spring Boot entry point, controllers, filters
│   ├── ai/               ← NL→metadata, NL→query (Spring AI)
│   ├── automation/       ← Validation, flows, user-code sandbox, outbox
│   ├── integrations/     ← Webhooks, HMAC, outbound HTTP
│   ├── metadata-engine/  ← Caffeine cache, ObjectDefinition, FieldDefinition
│   ├── persistence/      ← R2DBC, Flyway, CRUD, tenant isolation
│   ├── query-engine/     ← SOQL-like parser → parameterized SQL
│   └── security/         ← FLS, RLS, permission checker, Reactor Context
├── frontend/renderer/    ← React + Vite + TanStack Query
├── infrastructure/       ← Pulumi TypeScript (dev + prod stacks)
├── docs/
│   ├── developers/       ← You are here
│   ├── users/            ← End-user documentation
│   ├── adr/              ← Architecture Decision Records
│   └── contracts/        ← JSON Schema contracts
└── agents/               ← AI agent instructions and runbooks
```

## Seeded Data

Flyway `V2__seed_standard_objects.sql` pre-loads three standard objects for development:

| Object API Name | Label | Fields |
|---|---|---|
| `Account` | Account | `name`, `industry`, `annual_revenue`, `website` |
| `Contact` | Contact | `first_name`, `last_name`, `email`, `phone`, `account_id` |
| `Project` | Project | `name`, `status`, `start_date`, `end_date`, `owner_id` |

These are created under a seeded `dev-tenant` (see migration for the UUID).

## Troubleshooting

**Flyway migration fails on startup**  
Check that PostgreSQL is running: `docker compose ps`. The `osc` database and user must exist.

**TestContainers can't start PostgreSQL**  
Docker daemon must be running. On Linux, verify `docker ps` works without sudo. On Mac, ensure Docker Desktop is started.

**`.block()` ArchUnit failure**  
Search for `.block()` in your changes (`git diff | grep block`). Move the logic to a reactive chain using `flatMap`, `zipWith`, or `Mono.defer`.

**React dev proxy 502**  
Backend must be running on port 8080 before starting the frontend dev server.
