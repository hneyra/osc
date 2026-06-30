package dev.osc.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.osc.automation.dsl.ExpressionEvaluator;
import dev.osc.automation.dsl.ExpressionParser;
import dev.osc.automation.outbox.EventPublisher;
import dev.osc.automation.outbox.LoggingEventPublisher;
import dev.osc.automation.outbox.OutboxEvent;
import dev.osc.metadata.FieldDefinition;
import dev.osc.metadata.FieldType;
import dev.osc.metadata.MetadataEngine;
import dev.osc.metadata.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

@Component
@Primary
public class RollupEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RollupEventPublisher.class);

    private final LoggingEventPublisher loggingPublisher;
    private final MetadataEngine metadataEngine;
    private final RecordRepository recordRepository;
    private final DatabaseClient dbClient;
    private final ObjectMapper objectMapper;
    private final ExpressionParser expressionParser;
    private final ExpressionEvaluator expressionEvaluator;

    public RollupEventPublisher(LoggingEventPublisher loggingPublisher,
                                 MetadataEngine metadataEngine,
                                 RecordRepository recordRepository,
                                 DatabaseClient dbClient,
                                 ObjectMapper objectMapper) {
        this.loggingPublisher = loggingPublisher;
        this.metadataEngine = metadataEngine;
        this.recordRepository = recordRepository;
        this.dbClient = dbClient;
        this.objectMapper = objectMapper;
        this.expressionParser = new ExpressionParser();
        this.expressionEvaluator = new ExpressionEvaluator();
    }

    @Override
    public Mono<Void> publish(OutboxEvent event) {
        if ("ROLLUP_RECOMPUTE".equals(event.eventType())) {
            return processRollup(event)
                    .contextWrite(ctx -> ctx.put(TenantContext.TENANT_ID_KEY, event.tenantId().toString()));
        }
        return loggingPublisher.publish(event);
    }

    private Mono<Void> processRollup(OutboxEvent event) {
        UUID tenantId = event.tenantId();
        Map<String, Object> payload = event.payload();
        if (payload == null) return Mono.empty();

        String parentRecordIdStr = (String) payload.get("parentRecordId");
        String relationshipIdStr = (String) payload.get("relationshipId");
        String parentObjectIdStr = (String) payload.get("parentObjectId");

        if (parentRecordIdStr == null || relationshipIdStr == null || parentObjectIdStr == null) {
            log.warn("Invalid rollup event payload: {}", payload);
            return Mono.empty();
        }

        UUID parentRecordId = UUID.fromString(parentRecordIdStr);
        UUID relationshipId = UUID.fromString(relationshipIdStr);
        UUID parentObjectId = UUID.fromString(parentObjectIdStr);

        // 1. Find parent record
        return recordRepository.findById(parentRecordId)
                .flatMap(parentRecord ->
                        // 2. Find parent object fields
                        metadataEngine.findFields(tenantId, parentObjectId)
                                .collectList()
                                .flatMap(parentFields -> {
                                    List<FieldDefinition> rollupFields = parentFields.stream()
                                            .filter(f -> f.fieldType() == FieldType.ROLLUP)
                                            .filter(f -> {
                                                String configStr = f.config();
                                                if (configStr == null || configStr.isBlank()) return false;
                                                try {
                                                    JsonNode configNode = objectMapper.readTree(configStr);
                                                    JsonNode rollupNode = configNode.get("rollup");
                                                    if (rollupNode != null && rollupNode.has("relationshipId")) {
                                                        UUID relId = UUID.fromString(rollupNode.get("relationshipId").asText());
                                                        return relId.equals(relationshipId);
                                                    }
                                                } catch (Exception e) {
                                                    log.error("Failed to parse config for rollup field {}", f.apiName(), e);
                                                }
                                                return false;
                                            })
                                            .toList();

                                    if (rollupFields.isEmpty()) {
                                        return Mono.empty();
                                    }

                                    // 3. Find relationship
                                    return metadataEngine.getRelationships(tenantId, parentObjectId)
                                            .filter(r -> r.id().equals(relationshipId))
                                            .next()
                                            .flatMap(relationship ->
                                                    // 4. Find fields on child object
                                                    metadataEngine.findFields(tenantId, relationship.childObjectId())
                                                            .collectList()
                                                            .flatMap(childFields -> {
                                                                FieldDefinition lookupField = childFields.stream()
                                                                        .filter(f -> f.id().equals(relationship.fieldId()))
                                                                        .findFirst()
                                                                        .orElse(null);

                                                                if (lookupField == null) {
                                                                    log.error("Lookup field {} not found on child object {}",
                                                                            relationship.fieldId(), relationship.childObjectId());
                                                                    return Mono.empty();
                                                                }

                                                                String childLookupKey = lookupField.storageKey() != null
                                                                        ? lookupField.storageKey() : lookupField.apiName();

                                                                // 5. Query child records
                                                                return dbClient.sql("""
                                                                        SELECT id, name, owner_id, data::text, created_at, updated_at
                                                                        FROM record
                                                                        WHERE tenant_id = :tenantId
                                                                          AND object_id = :childObjectId
                                                                          AND data ->> :lookupKey = :parentRecordId
                                                                        """)
                                                                        .bind("tenantId", tenantId)
                                                                        .bind("childObjectId", relationship.childObjectId())
                                                                        .bind("lookupKey", childLookupKey)
                                                                        .bind("parentRecordId", parentRecordId.toString())
                                                                        .map(row -> {
                                                                            try {
                                                                                String json = row.get("data", String.class);
                                                                                Map<String, Object> data = json == null || json.isBlank()
                                                                                        ? Map.of()
                                                                                        : objectMapper.readValue(json, Map.class);
                                                                                return new ChildRecord(
                                                                                        row.get("id", UUID.class),
                                                                                        row.get("name", String.class),
                                                                                        row.get("owner_id", UUID.class),
                                                                                        data,
                                                                                        row.get("created_at", java.time.Instant.class),
                                                                                        row.get("updated_at", java.time.Instant.class)
                                                                                );
                                                                            } catch (Exception e) {
                                                                                throw new RuntimeException(e);
                                                                            }
                                                                        })
                                                                        .all()
                                                                        .collectList()
                                                                        .flatMap(childRecords -> {
                                                                            List<Map<String, Object>> childContexts = new ArrayList<>();
                                                                            for (ChildRecord cr : childRecords) {
                                                                                Map<String, Object> ctx = new HashMap<>();
                                                                                ctx.put("id", cr.id());
                                                                                ctx.put("name", cr.name());
                                                                                ctx.put("owner_id", cr.ownerId());
                                                                                if (cr.createdAt() != null) ctx.put("created_at", cr.createdAt());
                                                                                if (cr.updatedAt() != null) ctx.put("updated_at", cr.updatedAt());

                                                                                for (FieldDefinition cf : childFields) {
                                                                                    String cKey = cf.storageKey() != null ? cf.storageKey() : cf.apiName();
                                                                                    if (cr.data().containsKey(cKey)) {
                                                                                        ctx.put(cf.apiName(), cr.data().get(cKey));
                                                                                    }
                                                                                }
                                                                                childContexts.add(ctx);
                                                                            }

                                                                            Map<String, Object> dataPatch = new LinkedHashMap<>();
                                                                            for (FieldDefinition rf : rollupFields) {
                                                                                try {
                                                                                    JsonNode configNode = objectMapper.readTree(rf.config());
                                                                                    JsonNode rollupNode = configNode.get("rollup");
                                                                                    String aggregate = rollupNode.get("aggregate").asText();
                                                                                    String sourceField = rollupNode.has("sourceFieldApiName")
                                                                                            ? rollupNode.get("sourceFieldApiName").asText() : null;
                                                                                    String filterExpr = rollupNode.has("filterExpression")
                                                                                            ? rollupNode.get("filterExpression").asText() : null;

                                                                                    List<Map<String, Object>> filteredChildren = childContexts;
                                                                                    if (filterExpr != null && !filterExpr.isBlank()) {
                                                                                        var ast = expressionParser.parse(filterExpr);
                                                                                        filteredChildren = childContexts.stream()
                                                                                                .filter(ctx -> expressionEvaluator.evaluate(ast, ctx))
                                                                                                .toList();
                                                                                    }

                                                                                    Object rollupValue = calculateRollup(aggregate, sourceField, filteredChildren);
                                                                                    String storageKey = rf.storageKey() != null ? rf.storageKey() : rf.apiName();
                                                                                    dataPatch.put(storageKey, rollupValue);
                                                                                } catch (Exception e) {
                                                                                    log.error("Error computing rollup field {}", rf.apiName(), e);
                                                                                }
                                                                            }

                                                                            if (dataPatch.isEmpty()) {
                                                                                return Mono.empty();
                                                                            }

                                                                            return recordRepository.update(new RecordUpdateCommand(parentRecordId, null, null, dataPatch))
                                                                                    .then();
                                                                        });
                                                            })
                                            );
                                })
                )
                .then();
    }

    private Object calculateRollup(String aggregate, String sourceField, List<Map<String, Object>> children) {
        if ("COUNT".equalsIgnoreCase(aggregate)) {
            return (double) children.size();
        }

        if (sourceField == null || sourceField.isBlank()) {
            return null;
        }

        List<Double> values = children.stream()
                .map(ctx -> ctx.get(sourceField))
                .filter(Objects::nonNull)
                .map(this::getAsDouble)
                .filter(Objects::nonNull)
                .toList();

        if (values.isEmpty()) {
            if ("SUM".equalsIgnoreCase(aggregate)) {
                return 0.0;
            }
            return null;
        }

        return switch (aggregate.toUpperCase()) {
            case "SUM" -> values.stream().mapToDouble(Double::doubleValue).sum();
            case "MIN" -> values.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
            case "MAX" -> values.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
            case "AVG" -> values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            default -> null;
        };
    }

    private Double getAsDouble(Object val) {
        if (val == null) return null;
        if (val instanceof Number) return ((Number) val).doubleValue();
        try {
            return Double.parseDouble(val.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private record ChildRecord(
            UUID id,
            String name,
            UUID ownerId,
            Map<String, Object> data,
            java.time.Instant createdAt,
            java.time.Instant updatedAt
    ) {}
}
