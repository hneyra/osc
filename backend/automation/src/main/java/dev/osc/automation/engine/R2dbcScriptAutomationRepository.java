package dev.osc.automation.engine;

import io.r2dbc.spi.ConnectionFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Repository
public class R2dbcScriptAutomationRepository implements ScriptAutomationRepository {

    private final DatabaseClient db;

    public R2dbcScriptAutomationRepository(ConnectionFactory connectionFactory) {
        this.db = DatabaseClient.create(connectionFactory);
    }

    private ScriptDefinition mapRow(io.r2dbc.spi.Row row) {
        return new ScriptDefinition(
                row.get("id", UUID.class),
                row.get("tenant_id", UUID.class),
                row.get("object_id", UUID.class),
                row.get("object_api_name", String.class),
                row.get("kind", String.class),
                row.get("trigger_event", String.class),
                row.get("invocable_name", String.class),
                row.get("schedule_cron", String.class),
                row.get("source", String.class),
                Boolean.TRUE.equals(row.get("is_active", Boolean.class)),
                row.get("timeout_seconds", Integer.class) != null ? row.get("timeout_seconds", Integer.class) : 5
        );
    }

    private Mono<Void> activateTenant(UUID tenantId) {
        return db.sql("SELECT set_config('app.current_tenant', :tenantId, false)")
                .bind("tenantId", tenantId.toString())
                .fetch()
                .rowsUpdated()
                .then();
    }

    @Override
    public Flux<ScriptDefinition> findActiveByObjectAndTrigger(UUID tenantId, UUID objectId, TriggerType triggerType) {
        return activateTenant(tenantId)
                .thenMany(db.sql("""
                        SELECT s.id, s.tenant_id, s.object_id, s.kind, s.trigger_event, s.invocable_name,
                               s.schedule_cron, s.source, s.is_active, s.timeout_seconds, o.api_name as object_api_name
                        FROM md_script s
                        LEFT JOIN md_object o ON o.id = s.object_id
                        WHERE s.tenant_id = :tenantId
                          AND s.object_id = :objectId
                          AND s.trigger_event = :triggerEvent
                          AND s.is_active = true
                          AND s.kind = 'TRIGGER'
                        """)
                        .bind("tenantId", tenantId)
                        .bind("objectId", objectId)
                        .bind("triggerEvent", triggerType.name())
                        .map((row, rowMetadata) -> mapRow(row))
                        .all());
    }

    @Override
    public Mono<ScriptDefinition> findActiveInvocable(UUID tenantId, String invocableName) {
        return activateTenant(tenantId)
                .then(db.sql("""
                        SELECT s.id, s.tenant_id, s.object_id, s.kind, s.trigger_event, s.invocable_name,
                               s.schedule_cron, s.source, s.is_active, s.timeout_seconds, o.api_name as object_api_name
                        FROM md_script s
                        LEFT JOIN md_object o ON o.id = s.object_id
                        WHERE s.tenant_id = :tenantId
                          AND s.invocable_name = :invocableName
                          AND s.is_active = true
                          AND s.kind = 'INVOCABLE_ACTION'
                        """)
                        .bind("tenantId", tenantId)
                        .bind("invocableName", invocableName)
                        .map((row, rowMetadata) -> mapRow(row))
                        .one());
    }

    @Override
    public Flux<ScriptDefinition> findActiveScheduledAndBatch(UUID tenantId) {
        return activateTenant(tenantId)
                .thenMany(db.sql("""
                        SELECT s.id, s.tenant_id, s.object_id, s.kind, s.trigger_event, s.invocable_name,
                               s.schedule_cron, s.source, s.is_active, s.timeout_seconds, o.api_name as object_api_name
                        FROM md_script s
                        LEFT JOIN md_object o ON o.id = s.object_id
                        WHERE s.tenant_id = :tenantId
                          AND s.is_active = true
                          AND s.kind IN ('SCHEDULED', 'BATCH')
                        """)
                        .bind("tenantId", tenantId)
                        .map((row, rowMetadata) -> mapRow(row))
                        .all());
    }

    @Override
    public Flux<UUID> findAllTenantIds() {
        return db.sql("SELECT id FROM tenant")
                .map((row, rowMetadata) -> row.get("id", UUID.class))
                .all();
    }
}
