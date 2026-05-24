# ADR-004: Infrastructure as Code — Pulumi TypeScript

**Status:** Accepted
**Date:** 2026-05-24
**Deciders:** Project Lead

## Context

We need to provision and manage cloud infrastructure (PostgreSQL, compute, networking, secrets). Options:
1. Terraform (HCL)
2. Pulumi (TypeScript, Python, Go, etc.)
3. Manual cloud console

The team already has Pulumi experience and existing infrastructure in `hneyra/iaac`.

## Decision

**Pulumi TypeScript** in the `infrastructure/` directory.

- All infrastructure defined in TypeScript for type safety and IDE support.
- Existing services (databases, networking, registries) provisioned in `hneyra/iaac` are imported as stack references — not re-provisioned.
- Two stacks: `dev` (local/development) and `prod` (production).
- Secrets managed via Pulumi secrets (encrypted state).

## Consequences

**Good:**
- Type-safe infrastructure code — catches errors at compile time.
- Reuses existing `hneyra/iaac` services via stack references.
- Same language as frontend tooling (TypeScript).
- Pulumi state tracks what's deployed, enabling `pulumi destroy` safely.

**Bad:**
- Requires Pulumi CLI and account/self-hosted backend.
- TypeScript/Node.js overhead compared to HCL.
- State locking needed for collaborative teams.

## Stack References

```typescript
// Reference existing services from hneyra/iaac
const iaacStack = new pulumi.StackReference("hneyra/iaac/prod");
const postgresEndpoint = iaacStack.getOutput("postgresEndpoint");
const vpcId = iaacStack.getOutput("vpcId");
```
