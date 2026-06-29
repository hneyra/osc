# ADR-005: User Code Language — Kotlin Scripting (Apex-equivalent)

**Status:** Accepted
**Date:** 2026-06-28
**Deciders:** Project Lead
**Resolves:** Open point #1 in `docs/PROJECT.md` §13 ("Lenguaje de user-code: DSL propio vs. scripting embebido vs. WASM")

## Context

`docs/PROJECT.md` §7 left the user-code language as an open point, recommending to start with the DSL-only path (validation rules, declarative automations) and design `UserCodeExecutor` as a port for a future scripting/WASM/microVM backend.

The platform is evolving into an ERP-grade engine (`osc-platform`) that needs Salesforce-Apex-equivalent capability: tenant administrators and developers writing **Triggers**, service classes, **Invocable Actions**, **Batch jobs**, and **Scheduled jobs** against the dynamic object model — beyond what a boolean/arithmetic expression DSL can express. The AI layer also needs a language it can generate, statically validate, and explain.

Candidates evaluated:
1. **Keep DSL-only** — safe, but cannot express loops, multi-step batch logic, or calls into multiple records/services. Rejected: insufficient for "Apex-class" use cases.
2. **WASM / microVM (Wasmtime, Javy, etc.)** — strongest sandbox isolation, but requires a second toolchain/runtime, a separate compile pipeline per source language, and loses direct JVM interop with `MetadataEngine`/`DynamicPersistenceService`. Revisit if Kotlin Scripting's process-level isolation proves insufficient (see Future Work).
3. **Embedded JS (GraalJS)** — good sandboxing story, but disconnects the platform from the JVM type system and from Kotlin/Java tooling the team already uses.
4. **Kotlin Scripting (`kotlin-scripting-jvm-host` + custom `ScriptCompilationConfiguration`/`ScriptEvaluationConfiguration`)** — chosen.

## Decision

**Kotlin Scripting is the user-code language**, replacing the "Apex" role in the architecture. The DSL expression evaluator from §6.1 of `docs/PROJECT.md` is **not replaced** — it remains the engine for simple, declarative Validation Rules and field-update automations, because a whitelisted-grammar interpreter is strictly safer than a general-purpose language for that use case. Kotlin Scripting is layered **on top** as the path for anything that needs imperative logic: triggers, invocable actions, batch jobs, scheduled jobs.

```
Validation Rules, simple field automations  → DSL expression evaluator (§6.1, unchanged)
Triggers, Invocable Actions, Batch, Scheduled → Kotlin Scripting (this ADR)
```

### Why Kotlin Scripting fits the non-negotiable reactive stack

The hard constraint from ADR-003 / NNG-004 is **no blocking call on the WebFlux event loop**. Kotlin script *compilation* and *evaluation* are both blocking, synchronous JVM operations by design (`kotlin-scripting-jvm-host` has no reactive API). The engine in this ADR is designed so that constraint is never violated:

- Compilation and evaluation always run on `Schedulers.boundedElastic()`, wrapped in `Mono.fromCallable { ... }.subscribeOn(Schedulers.boundedElastic())`. They never run on a Netty event-loop thread.
- The `ExecutionContext` API exposed to scripts (§ below) is **synchronous on purpose** — it exposes blocking facades over `MetadataEngine`/`DynamicPersistenceService` that internally call `.block()` *inside the elastic scheduler thread only*, which is the one place `.block()` is allowed (it is off the event loop). This is the single, explicitly justified exception to NNG-004 and must be annotated `// NNG-004 exception: runs on boundedElastic, see ADR-005` at the call site, never elsewhere.
- A hard execution timeout (default 5s, tenant-configurable up to 30s) bounds how long an elastic thread can be occupied by a runaway script.

## Kotlin Scripting Engine — design

### 1. Compilation and caching

```
Script source (Kotlin text)
   │
   ▼
SHA-256(source + scriptDefinitionVersion) ──► cache key
   │
   ▼
CompiledScriptCache (Caffeine, tenant-scoped, max 2k entries / tenant, 1h TTL)
   │  miss                              hit
   ▼                                     │
KotlinJsr223OrJvmHost.compile(source)    │
   │ (blocking, boundedElastic)          │
   ▼                                     ▼
CompiledScript ─────────────────────────►│
   │
   ▼
Persist bytecode digest + compile diagnostics to md_script.compiled_at / compile_errors
```

- Compilation happens **on save** (admin/AI saves a script) and is cached; **never** recompiled per-execution.
- Compile errors block activation (`md_script.is_active = false` until a clean compile exists) — a script can never be enabled with a stale or failing compilation.
- Cache invalidation: on script update, or TTL expiry. Keyed by `(tenant_id, script_id, content_hash)` so two tenants never share a compiled class loader.

### 2. Sandbox

The sandbox is enforced at three levels, defense-in-depth like the rest of the platform:

| Level | Mechanism | Stops |
|---|---|---|
| Compile-time | Custom `ScriptCompilationConfiguration` restricting imports to an allowlist (`kotlin.*`, `dev.osc.scripting.api.*`, `java.time.*`, `java.math.*`, `java.util.*` collections) | `java.lang.reflect.*`, `java.io.*`, `java.net.*`, `java.lang.Process*`, arbitrary classpath access |
| Class-loading | Dedicated `URLClassLoader` per tenant with a restricted parent classloader (no access to the host application's full classpath) | Loading platform internals, other tenants' compiled scripts, JVM-internal classes |
| Runtime | `ExecutionGuard`: wall-clock timeout (default 5s) via `Mono.timeout()` on the elastic-scheduled call; CPU time check via `ThreadMXBean` sampling; max heap allocation per execution via a custom `-Xss`-bounded thread + periodic allocation check; max recursion depth counter injected into `ExecutionContext` | Infinite loops, fork bombs, memory exhaustion, stack overflow attacks |

No script ever gets:
- Network or filesystem access (no `java.net.*`, `java.io.*`, `java.nio.file.*` in the allowlist).
- Reflection (`java.lang.reflect.*`, `kotlin.reflect.*` excluded — breaks sandbox escape via reflection-based access to forbidden classes).
- The raw R2DBC `DatabaseClient`, JWT secrets, or any other module's credentials — only the `ExecutionContext` facade.
- Elevated permissions: every script executes with the invoking user's `SecurityContext` (object/field/record permissions still apply to every record access the script makes — FLS/RLS are not bypassable from script code).

### 3. `ExecutionContext` API surface (exposed to scripts)

```kotlin
interface ExecutionContext {
    val tenantId: UUID
    val currentUser: UserContext
    val trigger: TriggerContext?       // null outside trigger scripts

    fun <T> records(objectApiName: String): RecordOperations<T>
    fun log(level: LogLevel, message: String)
    fun now(): Instant
}

interface RecordOperations<T> {
    fun findById(id: UUID): DynamicRecord?
    fun query(soql: String): List<DynamicRecord>     // delegates to query-engine, same FLS/RLS as REST API
    fun insert(record: DynamicRecord): DynamicRecord
    fun update(record: DynamicRecord): DynamicRecord
    fun delete(id: UUID)
}

interface TriggerContext {
    val event: TriggerEvent             // BEFORE_INSERT, AFTER_UPDATE, ...
    val newRecords: List<DynamicRecord>
    val oldRecords: List<DynamicRecord> // empty on insert
}
```

`RecordOperations` is a **synchronous facade**: internally it calls the reactive `DynamicPersistenceService`/`QueryEngine` and `.block()`s — legally, because this code only ever executes on `Schedulers.boundedElastic()` (see justification above). Every call still goes through `MetadataEngine`, FLS, RLS, and tenant filtering exactly like the REST API path. Scripts cannot reach the database any other way.

### 4. Trigger / job execution flow

```
1. Record mutation reaches Automation Engine (same lifecycle as docs/PROJECT.md §6.2)
2. AutomationEngine resolves active md_script rows for (object_id, trigger_event)
3. For each: CompiledScriptCache.get(scriptId) -> CompiledScript (cache hit in the common path)
4. Mono.fromCallable { compiledScript.execute(executionContext) }
       .timeout(Duration.ofSeconds(script.timeoutSeconds))
       .subscribeOn(Schedulers.boundedElastic())
5. Execution result (mutations to newRecords, thrown exceptions) merged back into
   the same automation pipeline as declarative actions — a script throwing
   ScriptValidationException behaves exactly like a failed Validation Rule (422, transaction rolled back)
6. ScriptExecutionLog row written (script_id, trigger context, duration, outcome, log lines) — same transaction as the triggering record write
```

Batch and Scheduled scripts follow the same compile/cache/sandbox path but are invoked by a reactive scheduler (`automation` module) rather than the trigger pipeline, and operate over a `Flux` of record batches rather than a single trigger context.

### 5. Example scripts

**Trigger (before insert) — default a field:**

```kotlin
// md_script.kind = TRIGGER, trigger_event = BEFORE_INSERT, object = Opportunity__c
fun execute(ctx: ExecutionContext) {
    val trigger = ctx.trigger!!
    for (record in trigger.newRecords) {
        if (record.get<String>("stage__c") == null) {
            record.set("stage__c", "PROSPECTING")
        }
    }
}
```

**Batch job — recalculate a rollup nightly:**

```kotlin
// md_script.kind = BATCH, scheduled via md_script.schedule_cron
fun execute(ctx: ExecutionContext) {
    val accounts = ctx.records<DynamicRecord>("Account").query(
        "SELECT id FROM Account WHERE is_active__c = true"
    )
    for (account in accounts) {
        val total = ctx.records<DynamicRecord>("Opportunity__c").query(
            "SELECT amount__c FROM Opportunity__c WHERE account__c = '${account.id}' AND stage__c = 'WON'"
        ).sumOf { it.get<Double>("amount__c") ?: 0.0 }
        account.set("won_total__c", total)
        ctx.records<DynamicRecord>("Account").update(account)
    }
}
```

**Invocable Action — callable from a declarative Flow/automation:**

```kotlin
// md_script.kind = INVOCABLE_ACTION, invocable_name = "SendWelcomeTask"
fun invoke(ctx: ExecutionContext, input: Map<String, Any?>): Map<String, Any?> {
    val accountId = input["accountId"] as String
    val task = DynamicRecord(objectApiName = "Task__c").apply {
        set("subject__c", "Welcome call")
        set("related_to__c", accountId)
    }
    val created = ctx.records<DynamicRecord>("Task__c").insert(task)
    return mapOf("taskId" to created.id)
}
```

> Note: the string-interpolated SOQL in the batch example above is for illustration of script *ergonomics* only — the production `RecordOperations.query` implementation must bind `account.id` as a parameter through the same query-engine path the REST API uses (NNG-009/NNG-010 apply identically to script-issued queries). The script author never writes raw SQL; "SOQL" here is the same validated, parameterized DSL described in `docs/PROJECT.md` §5.

### 6. AI integration

The `ai` module (Spring AI) gains a fourth capability, alongside NL→metadata and NL→query (`docs/PROJECT.md` §8):

```
NL → Kotlin script proposal:
  1. User describes desired behavior ("when an Opportunity is won, create a follow-up task")
  2. AI generates a Kotlin script body against the documented ExecutionContext API
  3. Script is compiled (sandboxed compile-check only, not executed) — compile errors returned to AI for self-correction, up to N retries
  4. Static analysis pass: import allowlist check, forbidden-API scan, cyclomatic complexity / timeout-risk heuristic
  5. Proposal (source + compile diagnostics + static analysis report) shown to user for review — never auto-activated
  6. User edits/approves in the Script Editor (Monaco) → md_script row created with is_active = false
  7. Explicit human "Activate" action flips is_active = true after passing compile + static checks
```

This preserves NNG-017 ("AI is never on the critical data path"): AI-authored scripts never execute against real data without passing through the identical compile/sandbox/activation pipeline a human-written script does, and never run until a human activates them.

The AI can also be asked to **review** or **debug** an existing script: it receives the source, the latest `ScriptExecutionLog` failures, and returns a diff proposal — same human-confirmation gate applies before the diff is applied.

## Consequences

**Good:**
- Full Kotlin language (loops, collections, when-expressions, null-safety) for genuinely imperative business logic, while keeping the DSL for the simple/safe 90% case.
- JVM-native: no second runtime/toolchain, direct interop with `dev.osc.*` packages via the `ExecutionContext` facade, reuses the existing Gradle/Kotlin DSL build tooling already mandated by NNG-019.
- AI can generate, compile-check, and statically analyze scripts before any human or system ever executes them.
- Compiled-script caching keeps the steady-state cost of running a script close to a plain JVM method call.

**Bad / Risks:**
- Compilation is CPU-heavy (typically 100s of ms per unique script); must stay off the event loop and be cache-friendly — mitigated by the `CompiledScriptCache` and compile-on-save (not compile-on-execute).
- `.block()` inside `ExecutionContext`/`RecordOperations` is a deliberate, narrow exception to NNG-004; any code review touching `kotlin-scripting` must verify the call only ever happens inside a `boundedElastic`-scheduled lambda. This exception is invisible to the standard `NoBlockCallsRule` ArchUnit check on `src/main` of other modules and needs its own ArchUnit rule (`KotlinScriptingBlockingIsolationRule`) scoped to the `kotlin-scripting` module only, asserting the call sites are confined to the designated executor classes.
- Per-tenant classloader isolation adds JVM metaspace pressure at scale (many tenants × many scripts); revisit with a script classloader eviction policy if metaspace becomes a bottleneck.
- Sandbox escape risk is non-zero for any JVM-hosted scripting solution (unlike WASM/microVM, which run outside the JVM's class-loading boundary entirely). This is the primary argument for keeping the WASM/microVM door open (see Future Work) rather than treating Kotlin Scripting as the final word.

## Constraints

- Kotlin Scripting compilation/execution is **only** allowed inside the `backend/kotlin-scripting` module, behind the `UserCodeExecutor` port already specified in `docs/PROJECT.md` §7 — other modules call the port, never the Kotlin Scripting Engine directly.
- `.block()` is forbidden everywhere **except** inside classes explicitly annotated and reviewed under this ADR, all scheduled on `Schedulers.boundedElastic()`. No exceptions elsewhere in the codebase (NNG-004 stands everywhere else).
- Every script execution is audited in `script_execution_log` (tenant_id, script_id, trigger context, duration_ms, outcome, log lines) — same audit discipline as `automation_audit_log`.
- Hard limits (timeout, max heap delta, max recursion depth) are enforced server-side regardless of any value a tenant configures; tenant configuration can only tighten the platform default, never loosen it beyond the hard ceiling.
- See ADR-006 for the metadata tables (`md_script`, `script_execution_log`) backing this engine.

## Future Work

If per-tenant JVM isolation proves insufficient (observed sandbox escape attempts, metaspace exhaustion at scale), evaluate migrating script execution to a WASM runtime (option 2, rejected above) — `UserCodeExecutor` was already designed in `docs/PROJECT.md` §7 as a port specifically to allow this swap without touching callers.

## Implementation references

Not yet implemented — this ADR specifies the design ahead of the `backend/kotlin-scripting` module (tracked in `docs/PROJECT.md` Fase 5).
