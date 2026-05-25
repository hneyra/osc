package dev.osc.automation.outbox;

import reactor.core.publisher.Mono;

/**
 * Port — delivers an outbox event to its downstream consumer.
 * Phase 5 implementation logs the event; Phase 6 (Integrations) will replace with HTTP webhooks.
 */
public interface EventPublisher {
    Mono<Void> publish(OutboxEvent event);
}
