# ADR-001: Multi-tenancy Strategy — Shared Schema with PostgreSQL RLS

**Status:** Accepted
**Date:** 2026-05-24
**Deciders:** Project Lead

## Context

We need to support multiple tenants (organizations) in a single deployment. Three main strategies exist:
1. Separate database per tenant
2. Separate schema per tenant (schema-per-tenant)
3. Shared schema with tenant_id column + Row-Level Security

We're targeting tens of tenants and thousands of records. The system must prevent any data leakage between tenants.

## Decision

**Shared-schema + PostgreSQL RLS** (Option 3).

Every table (both metadata and data) includes `tenant_id UUID NOT NULL`. PostgreSQL Row-Level Security is enabled on all data tables:

```sql
ALTER TABLE record ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON record
    USING (tenant_id = current_setting('app.current_tenant')::uuid);
```

The application sets `app.current_tenant` per transaction via `SET LOCAL`, derived exclusively from the JWT claim:

```java
// Reactor Context propagation (reactive-safe, no ThreadLocal)
Mono<Void> setTenantSession(Connection conn) {
    return Mono.deferContextual(ctx ->
        Mono.from(conn.createStatement(
            "SET LOCAL app.current_tenant = '" + ctx.get(TENANT_KEY) + "'")
            .execute())
        .then()
    );
}
```

**Defense in depth:** Application layer also applies explicit `AND tenant_id = $tenantId` to every query, independently of RLS. Both layers must pass.

## Consequences

**Good:**
- Simple to operate: one database, one schema.
- Strong isolation: RLS is enforced at DB level, even if app-level filter bugs.
- Tenant ID from JWT only — no spoofing via parameters.
- Compatible with reactive R2DBC (connection-level session variable).

**Bad / Risks:**
- RLS misconfiguration could expose data — mitigated by defense-in-depth app filter + mandatory isolation tests.
- Cross-tenant reporting requires privileged bypass (admin-only, not in tenant path).

## Evolution Path

If a large tenant requires physical isolation, we can migrate that tenant to a dedicated schema or database without changing the logical model. The explicit `tenant_id` in every query makes this migration straightforward.
