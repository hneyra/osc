package dev.osc.integrations.webhook;

import reactor.core.publisher.Flux;

import java.util.UUID;

public interface WebhookSubscriptionRepository {
    Flux<WebhookSubscription> findActiveByTenantAndEventType(UUID tenantId, String eventType);
}
