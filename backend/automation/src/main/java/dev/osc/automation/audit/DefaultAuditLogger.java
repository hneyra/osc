package dev.osc.automation.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Fire-and-forget audit logger.
 * Errors are swallowed after logging — audit failures must not break the business operation.
 */
@Component
public class DefaultAuditLogger implements AuditLogger {

    private static final Logger log = LoggerFactory.getLogger(DefaultAuditLogger.class);

    private final AuditRepository auditRepository;

    public DefaultAuditLogger(AuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    @Override
    public Mono<Void> log(UUID tenantId, String eventType, String automationApiName,
                           Map<String, Object> context) {
        AutomationAuditEntry entry = new AutomationAuditEntry(
                UUID.randomUUID(), tenantId, eventType, automationApiName,
                Map.copyOf(context), Instant.now());

        return auditRepository.save(entry)
                .onErrorResume(ex -> {
                    log.warn("Audit log failed for automation '{}': {}", automationApiName, ex.getMessage());
                    return Mono.empty();
                });
    }
}
