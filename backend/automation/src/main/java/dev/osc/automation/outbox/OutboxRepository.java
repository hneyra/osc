package dev.osc.automation.outbox;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface OutboxRepository {
    Mono<OutboxEvent> save(OutboxEvent event);
    Flux<OutboxEvent> findPending(int limit);
    Mono<Void> markProcessed(UUID id);
    Mono<Void> markFailed(UUID id);
    Mono<Void> incrementAttempts(UUID id);
}
