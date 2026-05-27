# OSC — Developer & Architect Documentation

This directory is the authoritative reference for engineers building on or extending OSC.

## Navigation

| Document | Audience | Purpose |
|---|---|---|
| [architecture.md](architecture.md) | All engineers | System design, diagrams, key patterns |
| [getting-started.md](getting-started.md) | New contributors | Local setup, first run, dev workflow |
| [non-negotiables.md](non-negotiables.md) | All engineers | Hard constraints that cannot be violated |
| [modules-reference.md](modules-reference.md) | Backend engineers | Module contracts, dependencies, public APIs |
| [database-guide.md](database-guide.md) | Backend engineers | Schema, Flyway, R2DBC, JSONB patterns |
| [security-model.md](security-model.md) | Security / All | Multi-tenancy, FLS, RLS, JWT propagation |
| [testing-guide.md](testing-guide.md) | All engineers | Testing layers, TestContainers, ArchUnit |

## Related Docs

| Path | Contents |
|---|---|
| `docs/PROJECT.md` | Master source of truth (architecture + phase plan) |
| `docs/adr/` | Architecture Decision Records |
| `docs/contracts/` | JSON Schema contracts for metadata |
| `agents/CLAUDE.md` | Detailed instructions for AI coding agents |

## Phase Status

All 7 execution phases are complete. The platform is in hardening / production mode.

| Phase | Name | Status |
|---|---|---|
| 0 | Metadata model foundations | ✅ Complete |
| 1 | Dynamic persistence layer | ✅ Complete |
| 2 | Query engine + REST API | ✅ Complete |
| 3 | Frontend renderer | ✅ Complete |
| 4 | Security & permissions | ✅ Complete |
| 5 | Automation engine | ✅ Complete |
| 6 | Integrations + AI | ✅ Complete |
| 7 | Hardening & observability | ✅ Complete |

## Quick Reference

```bash
# Start local dependencies
docker compose up -d postgres

# Run all backend tests
./gradlew test

# Run a single module
./gradlew :backend:api:bootRun

# Start frontend
cd frontend/renderer && npm run dev

# Infrastructure preview
cd infrastructure && pulumi preview --stack dev
```
