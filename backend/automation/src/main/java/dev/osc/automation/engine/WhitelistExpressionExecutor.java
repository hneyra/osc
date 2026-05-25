package dev.osc.automation.engine;

import dev.osc.automation.dsl.DslSecurityException;
import dev.osc.automation.dsl.ExpressionEvaluator;
import dev.osc.automation.dsl.ExpressionParser;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Phase 5 implementation of UserCodeExecutor.
 * Accepts only the whitelisted Expression DSL — same syntax as validation rules.
 * Enforces a max expression length to prevent resource exhaustion.
 */
@Component
public class WhitelistExpressionExecutor implements UserCodeExecutor {

    private static final int MAX_EXPRESSION_LENGTH = 5000;

    private final ExpressionParser parser = new ExpressionParser();
    private final ExpressionEvaluator evaluator = new ExpressionEvaluator();

    @Override
    public UserCodeResult execute(String code, Map<String, Object> context) {
        if (code == null || code.isBlank()) {
            return UserCodeResult.failure("Code must not be blank");
        }
        if (code.length() > MAX_EXPRESSION_LENGTH) {
            return UserCodeResult.failure("Expression exceeds maximum allowed length of " + MAX_EXPRESSION_LENGTH);
        }
        try {
            var expr = parser.parse(code);
            boolean result = evaluator.evaluate(expr, context);
            return UserCodeResult.success(result);
        } catch (DslSecurityException e) {
            return UserCodeResult.failure("Security violation: " + e.getMessage());
        } catch (Exception e) {
            return UserCodeResult.failure("Evaluation error: " + e.getMessage());
        }
    }
}
