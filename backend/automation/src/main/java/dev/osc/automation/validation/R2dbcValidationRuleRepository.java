package dev.osc.automation.validation;

import io.r2dbc.spi.ConnectionFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

import java.util.UUID;

@Repository
public class R2dbcValidationRuleRepository implements ValidationRuleRepository {

    private final DatabaseClient db;

    public R2dbcValidationRuleRepository(ConnectionFactory connectionFactory) {
        this.db = DatabaseClient.create(connectionFactory);
    }

    @Override
    public Flux<ValidationRule> findActiveByObject(UUID tenantId, UUID objectId) {
        return db.sql("""
                SELECT id, tenant_id, object_id, api_name, condition_dsl, error_message, is_active
                FROM   md_validation_rule
                WHERE  tenant_id = :tenantId
                  AND  object_id = :objectId
                  AND  is_active = true
                ORDER BY api_name
                """)
                .bind("tenantId", tenantId)
                .bind("objectId", objectId)
                .map(row -> new ValidationRule(
                        row.get("id", UUID.class),
                        row.get("tenant_id", UUID.class),
                        row.get("object_id", UUID.class),
                        row.get("api_name", String.class),
                        row.get("condition_dsl", String.class),
                        row.get("error_message", String.class),
                        Boolean.TRUE.equals(row.get("is_active", Boolean.class))
                ))
                .all();
    }
}
