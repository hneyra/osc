package dev.osc.automation.engine;

import reactor.core.publisher.Flux;

import java.util.UUID;

public interface AutomationRepository {
    Flux<AutomationDefinition> findActiveByObjectAndTrigger(UUID tenantId, UUID objectId, TriggerType triggerType);
}
