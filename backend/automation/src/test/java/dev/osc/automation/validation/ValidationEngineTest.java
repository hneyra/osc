package dev.osc.automation.validation;

import dev.osc.automation.dsl.ExpressionEvaluator;
import dev.osc.automation.dsl.ExpressionParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.*;

/**
 * TDD — written before DefaultValidationEngine exists.
 */
class ValidationEngineTest {

    private ValidationRuleRepository ruleRepository;
    private DefaultValidationEngine engine;

    private static final UUID TENANT_ID = UUID.fromString("11111111-0000-0000-0000-000000000000");
    private static final UUID OBJECT_ID = UUID.fromString("22222222-0000-0000-0000-000000000000");

    @BeforeEach
    void setUp() {
        ruleRepository = mock(ValidationRuleRepository.class);
        engine = new DefaultValidationEngine(ruleRepository, new ExpressionParser(), new ExpressionEvaluator());
    }

    @Test
    @DisplayName("validate returns empty when record satisfies all rules")
    void validate_allPassed_emptyViolations() {
        ValidationRule rule = new ValidationRule(
                UUID.randomUUID(), TENANT_ID, OBJECT_ID,
                "name_required", "name != \"\"", "Name is required", true);
        when(ruleRepository.findActiveByObject(TENANT_ID, OBJECT_ID))
                .thenReturn(Flux.just(rule));

        Map<String, Object> record = Map.of("name", "Acme");

        StepVerifier.create(engine.validate(TENANT_ID, OBJECT_ID, record))
                .expectNextCount(0)
                .verifyComplete();
    }

    @Test
    @DisplayName("validate returns violation when rule condition fails")
    void validate_ruleViolated_returnsViolation() {
        ValidationRule rule = new ValidationRule(
                UUID.randomUUID(), TENANT_ID, OBJECT_ID,
                "revenue_positive", "revenue > 0", "Revenue must be positive", true);
        when(ruleRepository.findActiveByObject(TENANT_ID, OBJECT_ID))
                .thenReturn(Flux.just(rule));

        Map<String, Object> record = Map.of("revenue", -100);

        StepVerifier.create(engine.validate(TENANT_ID, OBJECT_ID, record))
                .expectNextMatches(v ->
                        v.ruleApiName().equals("revenue_positive") &&
                        v.errorMessage().equals("Revenue must be positive"))
                .verifyComplete();
    }

    @Test
    @DisplayName("validate returns multiple violations when multiple rules fail")
    void validate_multipleViolations() {
        ValidationRule r1 = new ValidationRule(
                UUID.randomUUID(), TENANT_ID, OBJECT_ID,
                "name_rule", "name != \"\"", "Name required", true);
        ValidationRule r2 = new ValidationRule(
                UUID.randomUUID(), TENANT_ID, OBJECT_ID,
                "score_rule", "score >= 0", "Score must be non-negative", true);
        when(ruleRepository.findActiveByObject(TENANT_ID, OBJECT_ID))
                .thenReturn(Flux.just(r1, r2));

        Map<String, Object> record = Map.of("name", "", "score", -5);

        StepVerifier.create(engine.validate(TENANT_ID, OBJECT_ID, record))
                .expectNextCount(2)
                .verifyComplete();
    }

    @Test
    @DisplayName("inactive rules are skipped")
    void validate_inactiveRule_skipped() {
        // Repository only returns active rules — this is enforced at repo level
        when(ruleRepository.findActiveByObject(TENANT_ID, OBJECT_ID))
                .thenReturn(Flux.empty());

        StepVerifier.create(engine.validate(TENANT_ID, OBJECT_ID, Map.of()))
                .expectNextCount(0)
                .verifyComplete();
    }
}
