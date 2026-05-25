package dev.osc.automation.engine;

import java.util.Map;

/**
 * Port — executes tenant-supplied code against a record context.
 * Phase 5: whitelist DSL expression evaluator.
 * Phase 6+: full sandbox (Graal polyglot or process isolation).
 */
public interface UserCodeExecutor {
    UserCodeResult execute(String code, Map<String, Object> context);
}
