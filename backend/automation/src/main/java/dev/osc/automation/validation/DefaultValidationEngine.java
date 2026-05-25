package dev.osc.automation.validation;

import dev.osc.automation.dsl.ExpressionEvaluator;
import dev.osc.automation.dsl.ExpressionParser;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.UUID;

/**
 * Evaluates all active validation rules for an object against a record.
 * A rule PASSES when its condition DSL evaluates to true (i.e. the data is valid).
 * A rule FAILS (produces a violation) when the condition evaluates to false.
 */
@Component
public class DefaultValidationEngine implements ValidationEngine {

    private final ValidationRuleRepository ruleRepository;
    private final ExpressionParser parser;
    private final ExpressionEvaluator evaluator;

    public DefaultValidationEngine(ValidationRuleRepository ruleRepository,
                                    ExpressionParser parser,
                                    ExpressionEvaluator evaluator) {
        this.ruleRepository = ruleRepository;
        this.parser = parser;
        this.evaluator = evaluator;
    }

    @Override
    public Flux<ValidationViolation> validate(UUID tenantId, UUID objectId, Map<String, Object> record) {
        return ruleRepository.findActiveByObject(tenantId, objectId)
                .filter(rule -> {
                    try {
                        var expr = parser.parse(rule.conditionDsl());
                        return !evaluator.evaluate(expr, record);
                    } catch (Exception e) {
                        return true; // treat broken rules as failing
                    }
                })
                .map(rule -> new ValidationViolation(rule.apiName(), rule.errorMessage()));
    }
}
