package dev.osc.integrations.webhook;

import dev.osc.integrations.http.OutboundHttpClient;
import dev.osc.integrations.signing.HmacSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WebhookDeliveryServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-0000-0000-0000-000000000000");

    private WebhookSubscriptionRepository subscriptionRepo;
    private OutboundHttpClient httpClient;
    private HmacSigner hmacSigner;
    private DefaultWebhookDeliveryService service;

    @BeforeEach
    void setUp() {
        subscriptionRepo = mock(WebhookSubscriptionRepository.class);
        httpClient       = mock(OutboundHttpClient.class);
        hmacSigner       = new HmacSigner();
        service          = new DefaultWebhookDeliveryService(subscriptionRepo, httpClient, hmacSigner);
    }

    @Test
    @DisplayName("deliver sends HTTP POST to each active subscriber for the event type")
    void deliver_postsToActiveSubscribers() {
        WebhookSubscription sub = new WebhookSubscription(
                UUID.randomUUID(), TENANT_ID, "record.created",
                "https://example.com/hook", "sig-secret", true);

        when(subscriptionRepo.findActiveByTenantAndEventType(TENANT_ID, "record.created"))
                .thenReturn(Flux.just(sub));
        when(httpClient.post(eq("https://example.com/hook"), anyString(), anyMap()))
                .thenReturn(Mono.just(200));

        StepVerifier.create(service.deliver(TENANT_ID, "record.created", "{\"id\":\"1\"}"))
                .verifyComplete();

        verify(httpClient).post(eq("https://example.com/hook"), anyString(), anyMap());
    }

    @Test
    @DisplayName("deliver adds X-Signature-SHA256 header")
    void deliver_addsSignatureHeader() {
        WebhookSubscription sub = new WebhookSubscription(
                UUID.randomUUID(), TENANT_ID, "record.created",
                "https://example.com/hook", "sig-secret", true);

        when(subscriptionRepo.findActiveByTenantAndEventType(TENANT_ID, "record.created"))
                .thenReturn(Flux.just(sub));
        when(httpClient.post(eq("https://example.com/hook"), anyString(), argThat(headers ->
                headers.containsKey("X-Signature-SHA256"))))
                .thenReturn(Mono.just(200));

        StepVerifier.create(service.deliver(TENANT_ID, "record.created", "{}"))
                .verifyComplete();

        verify(httpClient).post(eq("https://example.com/hook"), anyString(),
                argThat(h -> h.containsKey("X-Signature-SHA256")));
    }

    @Test
    @DisplayName("deliver continues to next subscriber if one fails")
    void deliver_continuesOnError() {
        WebhookSubscription sub1 = new WebhookSubscription(
                UUID.randomUUID(), TENANT_ID, "record.created",
                "https://fail.example.com/hook", "s1", true);
        WebhookSubscription sub2 = new WebhookSubscription(
                UUID.randomUUID(), TENANT_ID, "record.created",
                "https://ok.example.com/hook", "s2", true);

        when(subscriptionRepo.findActiveByTenantAndEventType(TENANT_ID, "record.created"))
                .thenReturn(Flux.just(sub1, sub2));
        when(httpClient.post(eq("https://fail.example.com/hook"), anyString(), anyMap()))
                .thenReturn(Mono.error(new RuntimeException("connection refused")));
        when(httpClient.post(eq("https://ok.example.com/hook"), anyString(), anyMap()))
                .thenReturn(Mono.just(200));

        StepVerifier.create(service.deliver(TENANT_ID, "record.created", "{}"))
                .verifyComplete();

        verify(httpClient, times(2)).post(anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("deliver completes empty when no subscriptions exist")
    void deliver_noSubscriptions() {
        when(subscriptionRepo.findActiveByTenantAndEventType(TENANT_ID, "record.updated"))
                .thenReturn(Flux.empty());

        StepVerifier.create(service.deliver(TENANT_ID, "record.updated", "{}"))
                .verifyComplete();

        verifyNoInteractions(httpClient);
    }
}
