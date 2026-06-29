package dev.osc.automation.dsl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FormulaParser and FormulaEvaluator Tests")
class FormulaEvaluatorTest {

    private FormulaParser parser;
    private FormulaEvaluator evaluator;

    @BeforeEach
    void setUp() {
        parser = new FormulaParser();
        evaluator = new FormulaEvaluator();
    }

    @Test
    @DisplayName("Basic arithmetic expressions evaluate correctly")
    void basicArithmetic() {
        var expr1 = parser.parse("amount * 0.1");
        assertEquals(10.0, evaluator.evaluate(expr1, Map.of("amount", 100.0)));

        var expr2 = parser.parse("price * quantity");
        assertEquals(50.0, evaluator.evaluate(expr2, Map.of("price", 10.0, "quantity", 5)));

        var expr3 = parser.parse("a + b - c");
        assertEquals(8.0, evaluator.evaluate(expr3, Map.of("a", 5, "b", 6, "c", 3)));
    }

    @Test
    @DisplayName("Arithmetic operator precedence and parentheses are respected")
    void arithmeticPrecedenceAndParentheses() {
        var expr1 = parser.parse("a + b * c");
        assertEquals(17.0, evaluator.evaluate(expr1, Map.of("a", 5, "b", 4, "c", 3)));

        var expr2 = parser.parse("(a + b) * c");
        assertEquals(27.0, evaluator.evaluate(expr2, Map.of("a", 5, "b", 4, "c", 3)));

        var expr3 = parser.parse("a / (b - c)");
        assertEquals(5.0, evaluator.evaluate(expr3, Map.of("a", 10, "b", 4, "c", 2)));
    }

    @Test
    @DisplayName("Negative numbers and unary minus are supported")
    void negativeNumbersAndUnaryMinus() {
        var expr1 = parser.parse("-amount");
        assertEquals(-100.0, evaluator.evaluate(expr1, Map.of("amount", 100.0)));

        var expr2 = parser.parse("amount * -1");
        assertEquals(-100.0, evaluator.evaluate(expr2, Map.of("amount", 100.0)));

        var expr3 = parser.parse("a + -b");
        assertEquals(5.0, evaluator.evaluate(expr3, Map.of("a", 10.0, "b", 5.0)));
    }

    @Test
    @DisplayName("Comparison operators evaluate correctly")
    void comparisons() {
        var expr1 = parser.parse("amount > 1000");
        assertEquals(true, evaluator.evaluate(expr1, Map.of("amount", 1500.0)));
        assertEquals(false, evaluator.evaluate(expr1, Map.of("amount", 500.0)));

        var expr2 = parser.parse("status == \"Sent\"");
        assertEquals(true, evaluator.evaluate(expr2, Map.of("status", "Sent")));
        assertEquals(false, evaluator.evaluate(expr2, Map.of("status", "Draft")));
    }

    @Test
    @DisplayName("Logical operators evaluate correctly")
    void logicalOperators() {
        var expr1 = parser.parse("is_active AND amount > 0");
        assertEquals(true, evaluator.evaluate(expr1, Map.of("is_active", true, "amount", 10.0)));
        assertEquals(false, evaluator.evaluate(expr1, Map.of("is_active", true, "amount", 0.0)));
        assertEquals(false, evaluator.evaluate(expr1, Map.of("is_active", false, "amount", 10.0)));

        var expr2 = parser.parse("NOT is_active");
        assertEquals(true, evaluator.evaluate(expr2, Map.of("is_active", false)));
        assertEquals(false, evaluator.evaluate(expr2, Map.of("is_active", true)));
    }

    @Test
    @DisplayName("Safe division by zero is handled")
    void divisionByZero() {
        var expr = parser.parse("amount / factor");
        assertEquals(0.0, evaluator.evaluate(expr, Map.of("amount", 100.0, "factor", 0.0)));
    }

    @Test
    @DisplayName("Null values evaluate gracefully to safe defaults")
    void nullHandling() {
        var expr1 = parser.parse("amount * 0.1");
        assertEquals(0.0, evaluator.evaluate(expr1, Map.of())); // amount is null -> 0.0 * 0.1 = 0.0

        var expr2 = parser.parse("status == null");
        assertEquals(true, evaluator.evaluate(expr2, Map.of())); // status is null -> null == null -> true
    }

    @Test
    @DisplayName("Cross-object references are rejected at parse/validation time")
    void crossObjectRejected() {
        assertThrows(DslSecurityException.class, () -> parser.parse("Account.Name"));
        assertThrows(DslSecurityException.class, () -> parser.parse("parent__r.name"));
        assertThrows(DslSecurityException.class, () -> parser.parse("owner.id"));
        assertThrows(DslSecurityException.class, () -> parser.parse("a.b + c"));
    }

    @Test
    @DisplayName("Disallowed constructs and security pattern violations are rejected")
    void securityViolationsRejected() {
        assertThrows(DslSecurityException.class, () -> parser.parse("new java.lang.ProcessBuilder()"));
        assertThrows(DslSecurityException.class, () -> parser.parse("System.exit(0)"));
        assertThrows(DslSecurityException.class, () -> parser.parse("amount; Object x"));
    }
}
