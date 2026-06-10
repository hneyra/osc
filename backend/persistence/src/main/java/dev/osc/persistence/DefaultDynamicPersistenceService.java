package dev.osc.persistence;

import dev.osc.metadata.*;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
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

    public DefaultDynamicPersistenceService(MetadataEngine metadataEngine,
                                             FieldCoercionEngine coercionEngine,
                                             RecordRepository recordRepository) {
        this.metadataEngine = metadataEngine;
        this.coercionEngine = coercionEngine;
        this.recordRepository = recordRepository;
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
                                                    );
                                                })
                                )
                );
    }

    @Override
    public Mono<RecordEntity> getRecord(UUID id) {
        return recordRepository.findById(id);
    }

    @Override
    public Flux<RecordEntity> listRecords(String objectApiName, PageRequest page) {
        return resolveTenantId()
                .flatMapMany(tenantId ->
                        metadataEngine.findObject(tenantId, objectApiName)
                                .switchIfEmpty(Mono.error(new ObjectNotFoundException(objectApiName)))
                                .flatMapMany(obj ->
                                        recordRepository.findByObjectId(obj.id(), page)
                                )
                );
    }

    @Override
    public Mono<RecordEntity> updateRecord(UUID id, Map<String, Object> dataPatch) {
        return recordRepository.update(new RecordUpdateCommand(id, null, null, dataPatch));
    }

    @Override
    public Mono<Void> deleteRecord(UUID id) {
        return recordRepository.delete(id);
    }

    private Mono<UUID> resolveTenantId() {
        return Mono.deferContextual(ctx ->
                Mono.just(UUID.fromString((String) ctx.get(TenantContext.TENANT_ID_KEY)))
        );
    }
}
