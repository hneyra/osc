package dev.osc.automation.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AutomationAuditEntry(
        UUID id,
        UUID tenantId,
        String eventType,
        String automationApiName,
        Map<String, Object> context,
        Instant createdAt
) {}
