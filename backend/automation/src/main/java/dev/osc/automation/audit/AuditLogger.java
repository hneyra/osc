package dev.osc.automation.audit;

import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

public interface AuditLogger {
    Mono<Void> log(UUID tenantId, String eventType, String automationApiName, Map<String, Object> context);
}
