package dev.osc.automation.validation;

import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.UUID;

public interface ValidationEngine {
    Flux<ValidationViolation> validate(UUID tenantId, UUID objectId, Map<String, Object> record);
}
