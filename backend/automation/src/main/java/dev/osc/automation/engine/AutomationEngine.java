package dev.osc.automation.engine;

import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

public interface AutomationEngine {
    /** Fires AFTER triggers (AFTER_INSERT, AFTER_UPDATE, AFTER_DELETE). */
    Mono<Void> fire(UUID tenantId, UUID objectId, TriggerType triggerType, Map<String, Object> record);

    /** Fires BEFORE triggers and returns potentially-modified record fields. */
    Mono<Map<String, Object>> fireBeforeWithContext(UUID tenantId, UUID objectId, TriggerType triggerType, Map<String, Object> record);
}
