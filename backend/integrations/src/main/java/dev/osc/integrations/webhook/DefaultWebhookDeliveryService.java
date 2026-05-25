package dev.osc.integrations.webhook;

import dev.osc.integrations.http.OutboundHttpClient;
import dev.osc.integrations.signing.HmacSigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

public class DefaultWebhookDeliveryService implements WebhookDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(DefaultWebhookDeliveryService.class);

    private final WebhookSubscriptionRepository subscriptionRepository;
    private final OutboundHttpClient httpClient;
    private final HmacSigner hmacSigner;

    public DefaultWebhookDeliveryService(
            WebhookSubscriptionRepository subscriptionRepository,
            OutboundHttpClient httpClient,
            HmacSigner hmacSigner) {
        this.subscriptionRepository = subscriptionRepository;
        this.httpClient = httpClient;
        this.hmacSigner = hmacSigner;
    }

    @Override
    public Mono<Void> deliver(UUID tenantId, String eventType, String payload) {
        return subscriptionRepository.findActiveByTenantAndEventType(tenantId, eventType)
                .flatMap(sub -> deliverToSubscription(sub, payload)
                        .onErrorResume(ex -> {
                            log.warn("Webhook delivery failed for {} ({}): {}", sub.targetUrl(), sub.id(), ex.getMessage());
                            return Mono.empty();
                        }))
                .then();
    }

    private Mono<Void> deliverToSubscription(WebhookSubscription sub, String payload) {
        String signature = hmacSigner.sign(payload, sub.signingSecret());
        Map<String, String> headers = Map.of(
                "Content-Type", "application/json",
                "X-Signature-SHA256", signature,
                "X-Event-Type", sub.eventType()
        );
        return httpClient.post(sub.targetUrl(), payload, headers).then();
    }
}
