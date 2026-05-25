package dev.osc.automation.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * TDD — written before OutboxWorker exists.
 */
class OutboxWorkerTest {

    private OutboxRepository outboxRepository;
    private EventPublisher eventPublisher;
    private OutboxWorker worker;

    private static final UUID TENANT_ID = UUID.fromString("11111111-0000-0000-0000-000000000000");

    @BeforeEach
    void setUp() {
        outboxRepository = mock(OutboxRepository.class);
        eventPublisher = mock(EventPublisher.class);
        worker = new OutboxWorker(outboxRepository, eventPublisher);
    }

    @Test
    @DisplayName("processPending delivers each pending event and marks it PROCESSED")
    void processPending_deliversAndMarksProcessed() {
        OutboxEvent event = new OutboxEvent(
                UUID.randomUUID(), TENANT_ID, "Record", UUID.randomUUID(),
                "RECORD_CREATED", Map.of("name", "Acme"),
                OutboxEventStatus.PENDING, 0, Instant.now(), null);

        when(outboxRepository.findPending(25)).thenReturn(Flux.just(event));
        when(eventPublisher.publish(event)).thenReturn(Mono.empty());
        when(outboxRepository.markProcessed(event.id())).thenReturn(Mono.empty());

        StepVerifier.create(worker.processPending())
                .verifyComplete();

        verify(eventPublisher).publish(event);
        verify(outboxRepository).markProcessed(event.id());
    }

    @Test
    @DisplayName("processPending increments attempt count and marks FAILED after delivery error")
    void processPending_deliveryError_marksFailed() {
        OutboxEvent event = new OutboxEvent(
                UUID.randomUUID(), TENANT_ID, "Record", UUID.randomUUID(),
                "RECORD_CREATED", Map.of(),
                OutboxEventStatus.PENDING, 2, Instant.now(), null);

        when(outboxRepository.findPending(25)).thenReturn(Flux.just(event));
        when(eventPublisher.publish(event)).thenReturn(Mono.error(new RuntimeException("network error")));
        when(outboxRepository.incrementAttempts(event.id())).thenReturn(Mono.empty());
        when(outboxRepository.markFailed(event.id())).thenReturn(Mono.empty());

        StepVerifier.create(worker.processPending())
                .verifyComplete();

        verify(outboxRepository).incrementAttempts(event.id());
        verify(outboxRepository).markFailed(event.id());
    }

    @Test
    @DisplayName("processPending handles empty queue without error")
    void processPending_emptyQueue_completesCleanly() {
        when(outboxRepository.findPending(25)).thenReturn(Flux.empty());

        StepVerifier.create(worker.processPending())
                .verifyComplete();

        verifyNoInteractions(eventPublisher);
    }
}
