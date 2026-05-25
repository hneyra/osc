package dev.osc.automation.audit;

import reactor.core.publisher.Mono;

public interface AuditRepository {
    Mono<Void> save(AutomationAuditEntry entry);
}
