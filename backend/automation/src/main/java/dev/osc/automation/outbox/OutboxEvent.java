package dev.osc.automation.outbox;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record OutboxEvent(
        UUID id,
        UUID tenantId,
        String aggregateType,
        UUID aggregateId,
        String eventType,
        Map<String, Object> payload,
        OutboxEventStatus status,
        int attempts,
        Instant createdAt,
        Instant processedAt
) {}
