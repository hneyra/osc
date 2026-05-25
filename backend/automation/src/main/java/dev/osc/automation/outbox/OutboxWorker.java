package dev.osc.automation.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Polls the outbox_event table for PENDING events and delivers them.
 * On success: marks PROCESSED. On failure: increments attempts and marks FAILED.
 * Scheduled every 5 seconds; uses reactive pipeline to avoid blocking.
 */
@Component
public class OutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);
    private static final int BATCH_SIZE = 25;

    private final OutboxRepository outboxRepository;
    private final EventPublisher eventPublisher;

    public OutboxWorker(OutboxRepository outboxRepository, EventPublisher eventPublisher) {
        this.outboxRepository = outboxRepository;
        this.eventPublisher = eventPublisher;
    }

    @Scheduled(fixedDelay = 5000)
    public void scheduledProcess() {
        processPending().subscribe(
                null,
                ex -> log.error("OutboxWorker error", ex));
    }

    public Mono<Void> processPending() {
        return outboxRepository.findPending(BATCH_SIZE)
                .flatMap(event ->
                        eventPublisher.publish(event)
                                .then(Mono.defer(() -> outboxRepository.markProcessed(event.id())))
                                .onErrorResume(ex -> handleDeliveryFailure(event, ex))
                )
                .then();
    }

    private Mono<Void> handleDeliveryFailure(OutboxEvent event, Throwable ex) {
        log.warn("Failed to deliver event {}: {}", event.id(), ex.getMessage());
        // Mono.when subscribes to both publishers and waits for both to complete.
        // incrementAttempts + markFailed are independent column updates on the same row.
        return Mono.when(
                outboxRepository.incrementAttempts(event.id()),
                outboxRepository.markFailed(event.id())
        );
    }
}
