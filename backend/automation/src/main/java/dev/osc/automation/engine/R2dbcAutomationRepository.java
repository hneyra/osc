package dev.osc.automation.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.ConnectionFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class R2dbcAutomationRepository implements AutomationRepository {

    private final DatabaseClient db;
    private final ObjectMapper objectMapper;

    public R2dbcAutomationRepository(ConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        this.db = DatabaseClient.create(connectionFactory);
        this.objectMapper = objectMapper;
    }

    @Override
    public Flux<AutomationDefinition> findActiveByObjectAndTrigger(UUID tenantId, UUID objectId,
                                                                     TriggerType triggerType) {
        return db.sql("""
                SELECT id, tenant_id, object_id, api_name, trigger_type, definition, is_active
                FROM   md_automation
                WHERE  tenant_id    = :tenantId
                  AND  object_id    = :objectId
                  AND  trigger_type = :triggerType
                  AND  is_active    = true
                ORDER BY api_name
                """)
                .bind("tenantId", tenantId)
                .bind("objectId", objectId)
                .bind("triggerType", triggerType.name())
                .map(row -> {
                    try {
                        String defJson = row.get("definition", String.class);
                        Map<String, Object> defMap = objectMapper.readValue(
                                defJson, new TypeReference<>() {});
                        List<Map<String, Object>> actionsRaw =
                                (List<Map<String, Object>>) defMap.getOrDefault("actions", List.of());
                        List<AutomationAction> actions = actionsRaw.stream()
                                .map(a -> new AutomationAction(
                                        ActionType.valueOf((String) a.get("type")),
                                        (Map<String, Object>) a.getOrDefault("params", Map.of())))
                                .toList();
                        return new AutomationDefinition(
                                row.get("id", UUID.class),
                                row.get("tenant_id", UUID.class),
                                row.get("object_id", UUID.class),
                                row.get("api_name", String.class),
                                TriggerType.valueOf(row.get("trigger_type", String.class)),
                                actions,
                                Boolean.TRUE.equals(row.get("is_active", Boolean.class)));
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to deserialize automation definition", e);
                    }
                })
                .all();
    }
}
