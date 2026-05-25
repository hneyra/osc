package dev.osc.automation.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.ConnectionFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public class R2dbcAuditRepository implements AuditRepository {

    private final DatabaseClient db;
    private final ObjectMapper objectMapper;

    public R2dbcAuditRepository(ConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        this.db = DatabaseClient.create(connectionFactory);
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> save(AutomationAuditEntry entry) {
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(entry.context()))
                .flatMap(contextJson ->
                        db.sql("""
                                INSERT INTO automation_audit_log
                                    (id, tenant_id, event_type, automation_api_name, context, created_at)
                                VALUES (:id, :tenantId, :eventType, :automationApiName, :context::jsonb, :createdAt)
                                """)
                                .bind("id", entry.id())
                                .bind("tenantId", entry.tenantId())
                                .bind("eventType", entry.eventType())
                                .bind("automationApiName", entry.automationApiName())
                                .bind("context", contextJson)
                                .bind("createdAt", entry.createdAt())
                                .then()
                );
    }
}
