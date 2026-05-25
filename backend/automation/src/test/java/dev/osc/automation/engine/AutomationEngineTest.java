package dev.osc.automation.engine;

import dev.osc.automation.outbox.OutboxEvent;
import dev.osc.automation.outbox.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * TDD — written before DefaultAutomationEngine exists.
 */
class AutomationEngineTest {

    private AutomationRepository automationRepository;
    private OutboxRepository outboxRepository;
    private DefaultAutomationEngine engine;

    private static final UUID TENANT_ID = UUID.fromString("11111111-0000-0000-0000-000000000000");
    private static final UUID OBJECT_ID = UUID.fromString("22222222-0000-0000-0000-000000000000");
    private static final UUID RECORD_ID = UUID.fromString("33333333-0000-0000-0000-000000000000");

    @BeforeEach
    void setUp() {
        automationRepository = mock(AutomationRepository.class);
        outboxRepository = mock(OutboxRepository.class);
        engine = new DefaultAutomationEngine(automationRepository, outboxRepository);
    }

    @Test
    @DisplayName("fire AFTER_INSERT triggers and publishes outbox event")
    void fire_afterInsert_publishesEvent() {
        AutomationDefinition def = new AutomationDefinition(
                UUID.randomUUID(), TENANT_ID, OBJECT_ID, "notify_on_create",
                TriggerType.AFTER_INSERT,
                List.of(new AutomationAction(ActionType.PUBLISH_EVENT, Map.of("eventType", "ACCOUNT_CREATED"))),
                true);

        when(automationRepository.findActiveByObjectAndTrigger(TENANT_ID, OBJECT_ID, TriggerType.AFTER_INSERT))
                .thenReturn(Flux.just(def));
        when(outboxRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        Map<String, Object> record = Map.of("id", RECORD_ID.toString(), "name", "Acme");

        StepVerifier.create(engine.fire(TENANT_ID, OBJECT_ID, TriggerType.AFTER_INSERT, record))
                .verifyComplete();

        verify(outboxRepository).save(any(OutboxEvent.class));
    }

    @Test
    @DisplayName("fire with no matching automations completes without error")
    void fire_noAutomations_completesCleanly() {
        when(automationRepository.findActiveByObjectAndTrigger(TENANT_ID, OBJECT_ID, TriggerType.AFTER_UPDATE))
                .thenReturn(Flux.empty());

        StepVerifier.create(engine.fire(TENANT_ID, OBJECT_ID, TriggerType.AFTER_UPDATE, Map.of()))
                .verifyComplete();

        verifyNoInteractions(outboxRepository);
    }

    @Test
    @DisplayName("fire BEFORE_INSERT returns modified record when UPDATE_FIELD action present")
    void fire_beforeInsert_updateFieldAction() {
        AutomationDefinition def = new AutomationDefinition(
                UUID.randomUUID(), TENANT_ID, OBJECT_ID, "set_default_status",
                TriggerType.BEFORE_INSERT,
                List.of(new AutomationAction(ActionType.UPDATE_FIELD,
                        Map.of("field", "status", "value", "New"))),
                true);

        when(automationRepository.findActiveByObjectAndTrigger(TENANT_ID, OBJECT_ID, TriggerType.BEFORE_INSERT))
                .thenReturn(Flux.just(def));

        Map<String, Object> record = Map.of("name", "Acme");

        StepVerifier.create(engine.fireBeforeWithContext(TENANT_ID, OBJECT_ID, TriggerType.BEFORE_INSERT, record))
                .expectNextMatches(result -> "New".equals(result.get("status")))
                .verifyComplete();
    }
}
