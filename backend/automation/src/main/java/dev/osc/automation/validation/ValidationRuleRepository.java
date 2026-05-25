package dev.osc.automation.validation;

import reactor.core.publisher.Flux;

import java.util.UUID;

public interface ValidationRuleRepository {
    Flux<ValidationRule> findActiveByObject(UUID tenantId, UUID objectId);
}
