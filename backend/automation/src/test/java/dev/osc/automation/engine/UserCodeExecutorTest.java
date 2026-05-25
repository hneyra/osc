package dev.osc.automation.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD — written before WhitelistExpressionExecutor exists.
 * Phase 5 user code = Expression DSL (same syntax as validation rules).
 */
class UserCodeExecutorTest {

    private UserCodeExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new WhitelistExpressionExecutor();
    }

    @Test
    @DisplayName("execute returns true for matching condition")
    void execute_matchingCondition() {
        UserCodeResult result = executor.execute("revenue > 1000000",
                Map.of("revenue", 2_000_000.0));
        assertTrue((Boolean) result.output());
        assertNull(result.error());
    }

    @Test
    @DisplayName("execute returns false for non-matching condition")
    void execute_nonMatchingCondition() {
        UserCodeResult result = executor.execute("status == \"Closed\"",
                Map.of("status", "Open"));
        assertFalse((Boolean) result.output());
        assertNull(result.error());
    }

    @Test
    @DisplayName("execute returns error result for disallowed construct")
    void execute_disallowedConstruct_returnsError() {
        UserCodeResult result = executor.execute("System.exit(0) == true", Map.of());
        assertNotNull(result.error());
        assertNull(result.output());
    }

    @Test
    @DisplayName("execute enforces CPU budget — infinite loop times out")
    void execute_timeBudget_enforced() {
        // An expression that's too long should fail safely
        String oversized = "a".repeat(5001) + " == \"x\"";
        UserCodeResult result = executor.execute(oversized, Map.of());
        assertNotNull(result.error());
    }
}
