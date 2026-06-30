package dev.osc.automation.engine;

import dev.osc.automation.outbox.OutboxEvent;
import dev.osc.automation.outbox.OutboxEventStatus;
import dev.osc.automation.outbox.OutboxRepository;
import dev.osc.security.SecurityContext;
import dev.osc.security.UserContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class DefaultAutomationEngine implements AutomationEngine {

    private final AutomationRepository automationRepository;
    private final OutboxRepository outboxRepository;
    private final ScriptAutomationRepository scriptAutomationRepository;
    private final UserCodeExecutor kotlinScriptExecutor;

    public DefaultAutomationEngine(AutomationRepository automationRepository,
                                    OutboxRepository outboxRepository,
                                    ScriptAutomationRepository scriptAutomationRepository,
                                    @Qualifier("kotlinScriptExecutor") UserCodeExecutor kotlinScriptExecutor) {
        this.automationRepository = automationRepository;
        this.outboxRepository = outboxRepository;
        this.scriptAutomationRepository = scriptAutomationRepository;
        this.kotlinScriptExecutor = kotlinScriptExecutor;
    }

    @Override
    public Mono<Void> fire(UUID tenantId, UUID objectId, TriggerType triggerType,
                            Map<String, Object> record) {
        // 1. Run declarative actions
        Mono<Void> declarativeMono = automationRepository
                .findActiveByObjectAndTrigger(tenantId, objectId, triggerType)
                .flatMap(def -> executeActions(def, tenantId, record))
                .then();

        // 2. Resolve UserContext and run active AFTER trigger scripts
        Mono<Void> scriptsMono = resolveUserContext(tenantId)
                .flatMap(userCtx -> scriptAutomationRepository.findActiveByObjectAndTrigger(tenantId, objectId, triggerType)
                        .flatMap(def -> {
                            Map<String, Object> executionContext = new HashMap<>();
                            executionContext.put("tenantId", tenantId);
                            executionContext.put("currentUser", userCtx);
                            executionContext.put("objectApiName", def.objectApiName());
                            executionContext.put("triggerEvent", triggerType.name());
                            executionContext.put("scriptId", def.id());
                            executionContext.put("timeoutSeconds", def.timeoutSeconds());
                            executionContext.put("newRecords", List.of(record));

                            return Mono.fromCallable(() -> kotlinScriptExecutor.execute(def.source(), executionContext))
                                    .subscribeOn(Schedulers.boundedElastic())
                                    .flatMap(result -> {
                                        if (result.error() != null) {
                                            return Mono.error(new ScriptValidationException(result.error()));
                                        }
                                        return Mono.empty();
                                    });
                        })
                        .then()
                );

        return Mono.when(declarativeMono, scriptsMono);
    }

    @SuppressWarnings("unchecked")
    @Override
    public Mono<Map<String, Object>> fireBeforeWithContext(UUID tenantId, UUID objectId,
                                                            TriggerType triggerType,
                                                            Map<String, Object> record) {
        // 1. Run declarative before-actions first
        Mono<Map<String, Object>> declarativeMono = automationRepository
                .findActiveByObjectAndTrigger(tenantId, objectId, triggerType)
                .reduce(new LinkedHashMap<>(record), (ctx, def) -> applyBeforeActions(def, ctx))
                .defaultIfEmpty(new LinkedHashMap<>(record))
                .cast(Map.class)
                .map(m -> (Map<String, Object>) m);

        // 2. Resolve UserContext and sequentially execute active trigger scripts
        return resolveUserContext(tenantId)
                .flatMap(userCtx -> declarativeMono.flatMap(currentRecord ->
                        scriptAutomationRepository.findActiveByObjectAndTrigger(tenantId, objectId, triggerType)
                                .collectList()
                                .flatMap(scriptDefs -> {
                                    if (scriptDefs.isEmpty()) {
                                        return Mono.just(currentRecord);
                                    }
                                    Mono<Map<String, Object>> currentMono = Mono.just(currentRecord);
                                    for (ScriptDefinition def : scriptDefs) {
                                        currentMono = currentMono.flatMap(rec -> {
                                            Map<String, Object> executionContext = new HashMap<>();
                                            executionContext.put("tenantId", tenantId);
                                            executionContext.put("currentUser", userCtx);
                                            executionContext.put("objectApiName", def.objectApiName());
                                            executionContext.put("triggerEvent", triggerType.name());
                                            executionContext.put("scriptId", def.id());
                                            executionContext.put("timeoutSeconds", def.timeoutSeconds());
                                            executionContext.put("newRecords", List.of(rec));

                                            return Mono.fromCallable(() -> kotlinScriptExecutor.execute(def.source(), executionContext))
                                                    .subscribeOn(Schedulers.boundedElastic())
                                                    .map(result -> {
                                                        if (result.error() != null) {
                                                            throw new ScriptValidationException(result.error());
                                                        }
                                                        Map<String, Object> output = (Map<String, Object>) result.output();
                                                        if (output != null && output.containsKey("newRecords")) {
                                                            List<Map<String, Object>> mutatedList = (List<Map<String, Object>>) output.get("newRecords");
                                                            if (mutatedList != null && !mutatedList.isEmpty()) {
                                                                return new LinkedHashMap<>(mutatedList.get(0));
                                                            }
                                                        }
                                                        return rec;
                                                    });
                                        });
                                    }
                                    return currentMono;
                                })
                ));
    }

    private Mono<Void> executeActions(AutomationDefinition def, UUID tenantId, Map<String, Object> record) {
        return reactor.core.publisher.Flux.fromIterable(def.actions())
                .flatMap(action -> switch (action.type()) {
                    case PUBLISH_EVENT -> publishEvent(def, tenantId, record, action);
                    case EXECUTE_CODE -> executeInvocableScript(tenantId, record, action);
                    case UPDATE_FIELD, CREATE_RECORD -> Mono.empty(); // AFTER phase: no record mutation
                })
                .then();
    }

    private Mono<Void> executeInvocableScript(UUID tenantId, Map<String, Object> record, AutomationAction action) {
        String invocableName = (String) action.params().get("invocableName");
        if (invocableName == null) {
            return Mono.empty();
        }
        return resolveUserContext(tenantId)
                .flatMap(userCtx -> scriptAutomationRepository.findActiveInvocable(tenantId, invocableName)
                        .flatMap(def -> {
                            Map<String, Object> executionContext = new HashMap<>();
                            executionContext.put("tenantId", tenantId);
                            executionContext.put("currentUser", userCtx);
                            executionContext.put("objectApiName", def.objectApiName());
                            executionContext.put("triggerEvent", "API");
                            executionContext.put("scriptId", def.id());
                            executionContext.put("timeoutSeconds", def.timeoutSeconds());
                            executionContext.put("newRecords", List.of(record));

                            return Mono.fromCallable(() -> kotlinScriptExecutor.execute(def.source(), executionContext))
                                    .subscribeOn(Schedulers.boundedElastic())
                                    .flatMap(result -> {
                                        if (result.error() != null) {
                                            return Mono.error(new ScriptValidationException(result.error()));
                                        }
                                        return Mono.empty();
                                    });
                        })
                );
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

    private Mono<UserContext> resolveUserContext(UUID tenantId) {
        return Mono.deferContextual(ctx -> {
            if (ctx.hasKey(SecurityContext.USER_CONTEXT_KEY)) {
                return Mono.just(ctx.get(SecurityContext.USER_CONTEXT_KEY));
            } else {
                return Mono.just(new UserContext(UUID.fromString("00000000-0000-0000-0000-000000000000"), tenantId));
            }
        });
    }
}
