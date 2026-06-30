package dev.osc.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.osc.automation.dsl.FormulaEvaluator;
import dev.osc.automation.dsl.FormulaParser;
import dev.osc.automation.outbox.OutboxEvent;
import dev.osc.automation.outbox.OutboxEventStatus;
import dev.osc.automation.outbox.OutboxRepository;
import dev.osc.metadata.*;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Default implementation of DynamicPersistenceService.
 *
 * Strategy pattern: field-level coercion is delegated to FieldCoercionEngine
 * so each FieldType's conversion rule is isolated and independently testable.
 *
 * Tenant ID is always read from Reactor Context — never passed as a parameter.
 */
@Service
public class DefaultDynamicPersistenceService implements DynamicPersistenceService {

    private final MetadataEngine metadataEngine;
    private final FieldCoercionEngine coercionEngine;
    private final RecordRepository recordRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final FormulaParser formulaParser;
    private final FormulaEvaluator formulaEvaluator;

    public DefaultDynamicPersistenceService(MetadataEngine metadataEngine,
                                             FieldCoercionEngine coercionEngine,
                                             RecordRepository recordRepository) {
        this(metadataEngine, coercionEngine, recordRepository, null);
    }

    public DefaultDynamicPersistenceService(MetadataEngine metadataEngine,
                                             FieldCoercionEngine coercionEngine,
                                             RecordRepository recordRepository,
                                             OutboxRepository outboxRepository) {
        this.metadataEngine = metadataEngine;
        this.coercionEngine = coercionEngine;
        this.recordRepository = recordRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = new ObjectMapper();
        this.formulaParser = new FormulaParser();
        this.formulaEvaluator = new FormulaEvaluator();
    }

    @Override
    public Mono<RecordEntity> createRecord(String objectApiName, Map<String, Object> data) {
        return resolveTenantId()
                .flatMap(tenantId ->
                        metadataEngine.findObject(tenantId, objectApiName)
                                .switchIfEmpty(Mono.error(new ObjectNotFoundException(objectApiName)))
                                .flatMap(obj ->
                                        metadataEngine.findFields(tenantId, obj.id()).collectList()
                                                .flatMap(fields -> {
                                                    Map<String, Object> coercedData = new LinkedHashMap<>();
                                                    for (FieldDefinition field : fields) {
                                                        Object value = data.get(field.apiName());
                                                        // Always coerce: the engine validates required (null -> Failure)
                                                        // and type, so required-field enforcement is not bypassed.
                                                        CoercionResult result = coercionEngine.coerce(field, value);
                                                        if (result instanceof CoercionResult.Failure f) {
                                                            return Mono.error(
                                                                    new FieldValidationException(field.apiName(), f.error())
                                                            );
                                                        }
                                                        Object typedValue = ((CoercionResult.Success) result).typedValue();
                                                        if (typedValue == null) continue; // optional field, no value supplied
                                                        String key = field.storageKey() != null
                                                                ? field.storageKey() : field.apiName();
                                                        coercedData.put(key, typedValue);
                                                    }
                                                    return recordRepository.insert(
                                                            new RecordInsertCommand(obj.id(), null, null, coercedData)
                                                    ).flatMap(insertedRecord ->
                                                            triggerRollupRecompute(insertedRecord, null)
                                                                    .thenReturn(insertedRecord)
                                                    );
                                                })
                                )
                );
    }

    @Override
    public Mono<RecordEntity> getRecord(UUID id) {
        return recordRepository.findById(id)
                .flatMap(record -> resolveTenantId()
                        .flatMap(tenantId -> enrichRecordWithFormulas(tenantId, record))
                );
    }

    @Override
    public Flux<RecordEntity> listRecords(String objectApiName, PageRequest page) {
        return resolveTenantId()
                .flatMapMany(tenantId ->
                        metadataEngine.findObject(tenantId, objectApiName)
                                .switchIfEmpty(Mono.error(new ObjectNotFoundException(objectApiName)))
                                .flatMapMany(obj -> {
                                    var fieldsFlux = metadataEngine.findFields(tenantId, obj.id());
                                    if (fieldsFlux == null) {
                                        return recordRepository.findByObjectId(obj.id(), page);
                                    }
                                    return fieldsFlux.collectList()
                                            .flatMapMany(fields -> {
                                                List<FieldDefinition> formulaFields = fields.stream()
                                                        .filter(f -> f.fieldType() == FieldType.FORMULA)
                                                        .toList();
                                                if (formulaFields.isEmpty()) {
                                                    return recordRepository.findByObjectId(obj.id(), page);
                                                }
                                                return recordRepository.findByObjectId(obj.id(), page)
                                                        .map(record -> enrichRecord(record, fields, formulaFields));
                                            })
                                            .onErrorResume(ex -> recordRepository.findByObjectId(obj.id(), page));
                                })
                );
    }

    @Override
    public Mono<RecordEntity> updateRecord(UUID id, Map<String, Object> dataPatch) {
        return recordRepository.findById(id)
                .switchIfEmpty(Mono.error(new ObjectNotFoundException("Record not found")))
                .flatMap(oldRecord ->
                        recordRepository.update(new RecordUpdateCommand(id, null, null, dataPatch))
                                .flatMap(updatedRecord ->
                                        triggerRollupRecompute(updatedRecord, oldRecord)
                                                .thenReturn(updatedRecord)
                                )
                );
    }

    @Override
    public Mono<Void> deleteRecord(UUID id) {
        return recordRepository.findById(id)
                .flatMap(oldRecord ->
                        recordRepository.delete(id)
                                .then(triggerRollupRecompute(oldRecord, null))
                );
    }

    private Mono<Void> triggerRollupRecompute(RecordEntity childRecord, RecordEntity oldChildRecord) {
        if (outboxRepository == null) {
            return Mono.empty();
        }
        return resolveTenantId()
                .flatMap(tenantId ->
                        metadataEngine.getRelationships(tenantId, childRecord.objectId())
                                .filter(rel -> "MASTER_DETAIL".equals(rel.relationshipType()))
                                .flatMap(rel ->
                                        metadataEngine.findFields(tenantId, childRecord.objectId())
                                                .filter(f -> f.id().equals(rel.fieldId()))
                                                .next()
                                                .flatMap(field -> {
                                                    String key = field.storageKey() != null ? field.storageKey() : field.apiName();

                                                    Object currentParentIdObj = childRecord.data().get(key);
                                                    Object oldParentIdObj = oldChildRecord != null ? oldChildRecord.data().get(key) : null;

                                                    UUID currentParentId = parseUuid(currentParentIdObj);
                                                    UUID oldParentId = parseUuid(oldParentIdObj);

                                                    return Flux.just(currentParentId, oldParentId)
                                                            .filter(Objects::nonNull)
                                                            .distinct()
                                                            .flatMap(parentId -> {
                                                                OutboxEvent event = new OutboxEvent(
                                                                        null,
                                                                        tenantId,
                                                                        "Record",
                                                                        parentId,
                                                                        "ROLLUP_RECOMPUTE",
                                                                        Map.of(
                                                                                "parentRecordId", parentId.toString(),
                                                                                "relationshipId", rel.id().toString(),
                                                                                "parentObjectId", rel.parentObjectId().toString()
                                                                        ),
                                                                        OutboxEventStatus.PENDING,
                                                                        0,
                                                                        null,
                                                                        null
                                                                );
                                                                return outboxRepository.save(event);
                                                            })
                                                            .then();
                                                })
                                )
                                .then()
                )
                .onErrorResume(ex -> Mono.empty()); // Never block the primary transaction
    }

    private UUID parseUuid(Object obj) {
        if (obj == null) return null;
        try {
            if (obj instanceof UUID) return (UUID) obj;
            return UUID.fromString(obj.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private Mono<RecordEntity> enrichRecordWithFormulas(UUID tenantId, RecordEntity record) {
        var fieldsFlux = metadataEngine.findFields(tenantId, record.objectId());
        if (fieldsFlux == null) {
            return Mono.just(record);
        }
        return fieldsFlux.collectList()
                .map(fields -> {
                    List<FieldDefinition> formulaFields = fields.stream()
                            .filter(f -> f.fieldType() == FieldType.FORMULA)
                            .toList();
                    if (formulaFields.isEmpty()) {
                        return record;
                    }
                    return enrichRecord(record, fields, formulaFields);
                })
                .defaultIfEmpty(record)
                .onErrorReturn(record);
    }

    private RecordEntity enrichRecord(RecordEntity record, List<FieldDefinition> allFields, List<FieldDefinition> formulaFields) {
        Map<String, Object> context = new HashMap<>();
        context.put("id", record.id());
        context.put("name", record.name());
        context.put("owner_id", record.ownerId());
        if (record.createdAt() != null) {
            context.put("created_at", record.createdAt().toString());
        }
        if (record.updatedAt() != null) {
            context.put("updated_at", record.updatedAt().toString());
        }

        // Put non-formula fields into context mapped by apiName
        for (FieldDefinition field : allFields) {
            if (field.fieldType() != FieldType.FORMULA) {
                String key = field.storageKey() != null ? field.storageKey() : field.apiName();
                if (record.data().containsKey(key)) {
                    context.put(field.apiName(), record.data().get(key));
                }
            }
        }

        // Evaluate formulas
        Map<String, Object> newData = new LinkedHashMap<>(record.data());
        for (FieldDefinition field : formulaFields) {
            Object value = null;
            try {
                String formula = null;
                String configStr = field.config();
                if (configStr != null && !configStr.isBlank()) {
                    com.fasterxml.jackson.databind.JsonNode configNode = objectMapper.readTree(configStr);
                    if (configNode.has("formula")) {
                        formula = configNode.get("formula").asText();
                    }
                }
                if (formula != null) {
                    var ast = formulaParser.parse(formula);
                    value = formulaEvaluator.evaluate(ast, context);
                }
            } catch (Exception e) {
                // Graceful fallback to null
            }
            context.put(field.apiName(), value);
            newData.put(field.apiName(), value);
        }

        return new RecordEntity(
                record.id(),
                record.tenantId(),
                record.objectId(),
                record.name(),
                record.ownerId(),
                newData,
                record.createdAt(),
                record.updatedAt()
        );
    }

    private Mono<UUID> resolveTenantId() {
        return Mono.deferContextual(ctx ->
                Mono.just(UUID.fromString((String) ctx.get(TenantContext.TENANT_ID_KEY)))
        );
    }
}
