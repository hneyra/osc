package dev.osc.automation.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * TDD — written before DefaultAuditLogger exists.
 */
class AuditLoggerTest {

    private AuditRepository auditRepository;
    private DefaultAuditLogger logger;

    private static final UUID TENANT_ID = UUID.fromString("11111111-0000-0000-0000-000000000000");

    @BeforeEach
    void setUp() {
        auditRepository = mock(AuditRepository.class);
        logger = new DefaultAuditLogger(auditRepository);
    }

    @Test
    @DisplayName("log saves an audit entry with correct fields")
    void log_savesEntry() {
        when(auditRepository.save(any())).thenReturn(Mono.empty());

        StepVerifier.create(logger.log(TENANT_ID, "automation_triggered",
                "notify_on_create", Map.of("recordId", "abc")))
                .verifyComplete();

        verify(auditRepository).save(argThat(entry ->
                entry.tenantId().equals(TENANT_ID) &&
                entry.eventType().equals("automation_triggered") &&
                entry.automationApiName().equals("notify_on_create")
        ));
    }

    @Test
    @DisplayName("log completes even if repository fails (fire-and-forget)")
    void log_repositoryError_doesNotPropagate() {
        when(auditRepository.save(any())).thenReturn(Mono.error(new RuntimeException("DB down")));

        StepVerifier.create(logger.log(TENANT_ID, "automation_triggered", "test_rule", Map.of()))
                .verifyComplete();
    }
}
