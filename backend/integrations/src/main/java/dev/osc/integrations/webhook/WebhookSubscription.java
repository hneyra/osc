package dev.osc.integrations.webhook;

import java.util.UUID;

public record WebhookSubscription(
        UUID id,
        UUID tenantId,
        String eventType,
        String targetUrl,
        String signingSecret,
        boolean active
) {}
