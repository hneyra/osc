package dev.osc.integrations.webhook;

import reactor.core.publisher.Mono;

import java.util.UUID;

public interface WebhookDeliveryService {
    Mono<Void> deliver(UUID tenantId, String eventType, String payload);
}
