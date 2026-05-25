package dev.osc.automation.engine;

import dev.osc.automation.outbox.OutboxEvent;
import dev.osc.automation.outbox.OutboxEventStatus;
import dev.osc.automation.outbox.OutboxRepository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class DefaultAutomationEngine implements AutomationEngine {

    private final AutomationRepository automationRepository;
    private final OutboxRepository outboxRepository;

    public DefaultAutomationEngine(AutomationRepository automationRepository,
                                    OutboxRepository outboxRepository) {
        this.automationRepository = automationRepository;
        this.outboxRepository = outboxRepository;
    }

    @Override
    public Mono<Void> fire(UUID tenantId, UUID objectId, TriggerType triggerType,
                            Map<String, Object> record) {
        return automationRepository
                .findActiveByObjectAndTrigger(tenantId, objectId, triggerType)
                .flatMap(def -> executeActions(def, tenantId, record))
                .then();
    }

    @Override
    public Mono<Map<String, Object>> fireBeforeWithContext(UUID tenantId, UUID objectId,
                                                            TriggerType triggerType,
                                                            Map<String, Object> record) {
        return automationRepository
                .findActiveByObjectAndTrigger(tenantId, objectId, triggerType)
                .reduce(new LinkedHashMap<>(record), (ctx, def) -> applyBeforeActions(def, ctx))
                .defaultIfEmpty(new LinkedHashMap<>(record))
                .cast(Map.class)
                .map(m -> (Map<String, Object>) m);
    }

    private Mono<Void> executeActions(AutomationDefinition def, UUID tenantId, Map<String, Object> record) {
        return reactor.core.publisher.Flux.fromIterable(def.actions())
                .flatMap(action -> switch (action.type()) {
                    case PUBLISH_EVENT -> publishEvent(def, tenantId, record, action);
                    case UPDATE_FIELD, CREATE_RECORD -> Mono.empty(); // AFTER phase: no record mutation
                })
                .then();
    }

    private Mono<Void> publishEvent(AutomationDefinition def, UUID tenantId,
                                     Map<String, Object> record, AutomationAction action) {
        String eventType = (String) action.params().getOrDefault("eventType", def.apiName().toUpperCase());
        OutboxEvent event = new OutboxEvent(
                null, tenantId, "Record",
                UUID.fromString(record.getOrDefault("id", UUID.randomUUID()).toString()),
                eventType, Map.copyOf(record),
                OutboxEventStatus.PENDING, 0, null, null);
        return outboxRepository.save(event).then();
    }

    @SuppressWarnings("unchecked")
    private LinkedHashMap<String, Object> applyBeforeActions(AutomationDefinition def,
                                                               LinkedHashMap<String, Object> ctx) {
        for (AutomationAction action : def.actions()) {
            if (action.type() == ActionType.UPDATE_FIELD) {
                String field = (String) action.params().get("field");
                Object value = action.params().get("value");
                if (field != null) ctx.put(field, value);
            }
        }
        return ctx;
    }
}
