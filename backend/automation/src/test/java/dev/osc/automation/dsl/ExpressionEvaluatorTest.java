package dev.osc.automation.dsl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD — written before ExpressionEvaluator exists.
 *
 * DSL syntax examples:
 *   name == "Acme"
 *   revenue > 1000000
 *   status != "Closed"
 *   name == "Acme" AND industry == "Tech"
 *   NOT active == true
 *   score >= 90 OR priority == "HIGH"
 */
class ExpressionEvaluatorTest {

    private ExpressionParser parser;
    private ExpressionEvaluator evaluator;

    @BeforeEach
    void setUp() {
        parser = new ExpressionParser();
        evaluator = new ExpressionEvaluator();
    }

    // ── String comparisons ────────────────────────────────────────────────────

    @Test
    @DisplayName("== with matching string returns true")
    void stringEquals_match() {
        var expr = parser.parse("name == \"Acme\"");
        assertTrue(evaluator.evaluate(expr, Map.of("name", "Acme")));
    }

    @Test
    @DisplayName("== with non-matching string returns false")
    void stringEquals_noMatch() {
        var expr = parser.parse("name == \"Acme\"");
        assertFalse(evaluator.evaluate(expr, Map.of("name", "Other")));
    }

    @Test
    @DisplayName("!= returns true when values differ")
    void stringNotEquals() {
        var expr = parser.parse("status != \"Closed\"");
        assertTrue(evaluator.evaluate(expr, Map.of("status", "Open")));
    }

    // ── Numeric comparisons ───────────────────────────────────────────────────

    @Test
    @DisplayName("> returns true when field value is greater")
    void numericGreaterThan() {
        var expr = parser.parse("revenue > 1000000");
        assertTrue(evaluator.evaluate(expr, Map.of("revenue", 2000000.0)));
    }

    @Test
    @DisplayName(">= returns true when field value equals threshold")
    void numericGreaterOrEqual_equal() {
        var expr = parser.parse("score >= 90");
        assertTrue(evaluator.evaluate(expr, Map.of("score", 90)));
    }

    @Test
    @DisplayName("< returns false when field value is greater")
    void numericLessThan_false() {
        var expr = parser.parse("score < 50");
        assertFalse(evaluator.evaluate(expr, Map.of("score", 75)));
    }

    @Test
    @DisplayName("<= returns true when field value equals threshold")
    void numericLessOrEqual_equal() {
        var expr = parser.parse("count <= 10");
        assertTrue(evaluator.evaluate(expr, Map.of("count", 10)));
    }

    // ── Boolean ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("boolean == true evaluates correctly")
    void booleanEquals() {
        var expr = parser.parse("active == true");
        assertTrue(evaluator.evaluate(expr, Map.of("active", true)));
        assertFalse(evaluator.evaluate(expr, Map.of("active", false)));
    }

    // ── Logical operators ─────────────────────────────────────────────────────

    @Test
    @DisplayName("AND returns true only when both sides are true")
    void logicalAnd() {
        var expr = parser.parse("name == \"Acme\" AND active == true");
        assertTrue(evaluator.evaluate(expr, Map.of("name", "Acme", "active", true)));
        assertFalse(evaluator.evaluate(expr, Map.of("name", "Acme", "active", false)));
        assertFalse(evaluator.evaluate(expr, Map.of("name", "Other", "active", true)));
    }

    @Test
    @DisplayName("OR returns true when at least one side is true")
    void logicalOr() {
        var expr = parser.parse("name == \"Acme\" OR name == \"Beta\"");
        assertTrue(evaluator.evaluate(expr, Map.of("name", "Acme")));
        assertTrue(evaluator.evaluate(expr, Map.of("name", "Beta")));
        assertFalse(evaluator.evaluate(expr, Map.of("name", "Other")));
    }

    @Test
    @DisplayName("NOT negates a simple expression")
    void logicalNot() {
        var expr = parser.parse("NOT status == \"Closed\"");
        assertTrue(evaluator.evaluate(expr, Map.of("status", "Open")));
        assertFalse(evaluator.evaluate(expr, Map.of("status", "Closed")));
    }

    // ── Missing field ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("comparison with missing field returns false")
    void missingField_returnsFalse() {
        var expr = parser.parse("name == \"Acme\"");
        assertFalse(evaluator.evaluate(expr, Map.of()));
    }

    // ── Security: only whitelisted operators ──────────────────────────────────

    @Test
    @DisplayName("parse throws DslSecurityException for function call syntax")
    void functionCall_throwsSecurityException() {
        assertThrows(DslSecurityException.class, () ->
                parser.parse("System.exit(0) == true"));
    }

    @Test
    @DisplayName("parse throws DslSecurityException for dot-method syntax")
    void methodCall_throwsSecurityException() {
        assertThrows(DslSecurityException.class, () ->
                parser.parse("name.contains(\"x\") == true"));
    }
}
