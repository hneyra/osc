package dev.osc.automation.engine;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface ScriptAutomationRepository {
    Flux<ScriptDefinition> findActiveByObjectAndTrigger(UUID tenantId, UUID objectId, TriggerType triggerType);
    Mono<ScriptDefinition> findActiveInvocable(UUID tenantId, String invocableName);
    Flux<ScriptDefinition> findActiveScheduledAndBatch(UUID tenantId);
    Flux<UUID> findAllTenantIds();
}
