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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * TDD — written before DefaultAutomationEngine exists.
 * Expanded to comprehensively test Kotlin scripting wiring, validation propagation,
 * invocable actions, and multi-tenant boundaries.
 */
class AutomationEngineTest {

    private AutomationRepository automationRepository;
    private OutboxRepository outboxRepository;
    private ScriptAutomationRepository scriptAutomationRepository;
    private UserCodeExecutor kotlinScriptExecutor;
    private DefaultAutomationEngine engine;

    private static final UUID TENANT_ID = UUID.fromString("11111111-0000-0000-0000-000000000000");
    private static final UUID OBJECT_ID = UUID.fromString("22222222-0000-0000-0000-000000000000");
    private static final UUID RECORD_ID = UUID.fromString("33333333-0000-0000-0000-000000000000");

    @BeforeEach
    void setUp() {
        automationRepository = mock(AutomationRepository.class);
        outboxRepository = mock(OutboxRepository.class);
        scriptAutomationRepository = mock(ScriptAutomationRepository.class);
        kotlinScriptExecutor = mock(UserCodeExecutor.class);
        engine = new DefaultAutomationEngine(automationRepository, outboxRepository, scriptAutomationRepository, kotlinScriptExecutor);

        // Default empty stubs
        when(automationRepository.findActiveByObjectAndTrigger(any(), any(), any())).thenReturn(Flux.empty());
        when(scriptAutomationRepository.findActiveByObjectAndTrigger(any(), any(), any())).thenReturn(Flux.empty());
        when(scriptAutomationRepository.findActiveInvocable(any(), any())).thenReturn(Mono.empty());
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

    @Test
    @DisplayName("fireBeforeWithContext sequentially executes multiple trigger scripts and merges mutations")
    void fireBefore_sequentialScriptExecution() {
        ScriptDefinition script1 = new ScriptDefinition(
                UUID.randomUUID(), TENANT_ID, OBJECT_ID, "Account", "TRIGGER", "BEFORE_INSERT",
                null, null, "val newRec = ctx.trigger.newRecords[0]; newRec.fields[\"foo\"] = \"bar\"", true, 5
        );
        ScriptDefinition script2 = new ScriptDefinition(
                UUID.randomUUID(), TENANT_ID, OBJECT_ID, "Account", "TRIGGER", "BEFORE_INSERT",
                null, null, "val newRec = ctx.trigger.newRecords[0]; newRec.fields[\"baz\"] = \"qux\"", true, 5
        );

        when(scriptAutomationRepository.findActiveByObjectAndTrigger(TENANT_ID, OBJECT_ID, TriggerType.BEFORE_INSERT))
                .thenReturn(Flux.just(script1, script2));

        // Stub executor mock to return mutated outputs simulated from execution
        when(kotlinScriptExecutor.execute(eq(script1.source()), any()))
                .thenReturn(UserCodeResult.success(Map.of("newRecords", List.of(Map.of("name", "Acme", "foo", "bar")))));

        when(kotlinScriptExecutor.execute(eq(script2.source()), any()))
                .thenReturn(UserCodeResult.success(Map.of("newRecords", List.of(Map.of("name", "Acme", "foo", "bar", "baz", "qux")))));

        Map<String, Object> record = Map.of("name", "Acme");

        StepVerifier.create(engine.fireBeforeWithContext(TENANT_ID, OBJECT_ID, TriggerType.BEFORE_INSERT, record))
                .assertNext(result -> {
                    assertEquals("Acme", result.get("name"));
                    assertEquals("bar", result.get("foo"));
                    assertEquals("qux", result.get("baz"));
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("fireBeforeWithContext propagates validation failures as ScriptValidationException")
    void fireBefore_propagatesValidationFailure() {
        ScriptDefinition script = new ScriptDefinition(
                UUID.randomUUID(), TENANT_ID, OBJECT_ID, "Account", "TRIGGER", "BEFORE_INSERT",
                null, null, "ctx.trigger.addError(\"Invalid account name\")", true, 5
        );

        when(scriptAutomationRepository.findActiveByObjectAndTrigger(TENANT_ID, OBJECT_ID, TriggerType.BEFORE_INSERT))
                .thenReturn(Flux.just(script));

        when(kotlinScriptExecutor.execute(eq(script.source()), any()))
                .thenReturn(UserCodeResult.failure("Validation failed: Invalid account name"));

        Map<String, Object> record = Map.of("name", "Acme");

        StepVerifier.create(engine.fireBeforeWithContext(TENANT_ID, OBJECT_ID, TriggerType.BEFORE_INSERT, record))
                .expectError(ScriptValidationException.class)
                .verify();
    }

    @Test
    @DisplayName("fire AFTER_INSERT executes trigger scripts correctly")
    void fireAfter_executesScript() {
        ScriptDefinition script = new ScriptDefinition(
                UUID.randomUUID(), TENANT_ID, OBJECT_ID, "Account", "TRIGGER", "AFTER_INSERT",
                null, null, "println(\"Hello AFTER\")", true, 5
        );

        when(scriptAutomationRepository.findActiveByObjectAndTrigger(TENANT_ID, OBJECT_ID, TriggerType.AFTER_INSERT))
                .thenReturn(Flux.just(script));

        when(kotlinScriptExecutor.execute(eq(script.source()), any()))
                .thenReturn(UserCodeResult.success(Map.of()));

        Map<String, Object> record = Map.of("id", RECORD_ID.toString(), "name", "Acme");

        StepVerifier.create(engine.fire(TENANT_ID, OBJECT_ID, TriggerType.AFTER_INSERT, record))
                .verifyComplete();

        verify(kotlinScriptExecutor).execute(eq(script.source()), any());
    }

    @Test
    @DisplayName("declarative EXECUTE_CODE action triggers active invocable script execution")
    void executeCode_invokesActiveInvocableScript() {
        AutomationDefinition flow = new AutomationDefinition(
                UUID.randomUUID(), TENANT_ID, OBJECT_ID, "flow_execute_custom",
                TriggerType.AFTER_INSERT,
                List.of(new AutomationAction(ActionType.EXECUTE_CODE, Map.of("invocableName", "sendToSlack"))),
                true);

        ScriptDefinition invocableScript = new ScriptDefinition(
                UUID.randomUUID(), TENANT_ID, OBJECT_ID, "Account", "INVOCABLE_ACTION", null,
                "sendToSlack", null, "slackNotify()", true, 5
        );

        when(automationRepository.findActiveByObjectAndTrigger(TENANT_ID, OBJECT_ID, TriggerType.AFTER_INSERT))
                .thenReturn(Flux.just(flow));

        when(scriptAutomationRepository.findActiveInvocable(TENANT_ID, "sendToSlack"))
                .thenReturn(Mono.just(invocableScript));

        when(kotlinScriptExecutor.execute(eq(invocableScript.source()), any()))
                .thenReturn(UserCodeResult.success(Map.of()));

        Map<String, Object> record = Map.of("id", RECORD_ID.toString(), "name", "Acme");

        StepVerifier.create(engine.fire(TENANT_ID, OBJECT_ID, TriggerType.AFTER_INSERT, record))
                .verifyComplete();

        verify(kotlinScriptExecutor).execute(eq(invocableScript.source()), any());
    }

    @Test
    @DisplayName("script execution respects strict tenant boundaries and does not run for other tenants")
    void strict_tenantBoundariesEnforced() {
        UUID otherTenantId = UUID.randomUUID();

        // Querying for otherTenantId returns no scripts for TENANT_ID
        when(scriptAutomationRepository.findActiveByObjectAndTrigger(otherTenantId, OBJECT_ID, TriggerType.BEFORE_INSERT))
                .thenReturn(Flux.empty());

        Map<String, Object> record = Map.of("name", "Acme");

        StepVerifier.create(engine.fireBeforeWithContext(otherTenantId, OBJECT_ID, TriggerType.BEFORE_INSERT, record))
                .expectNextMatches(result -> result.equals(record)) // completely unchanged
                .verifyComplete();

        verifyNoInteractions(kotlinScriptExecutor);
    }
}
