package dev.osc.persistence;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * Primary port — application-level CRUD for dynamic records.
 * Tenant ID is propagated via Reactor Context; callers must not pass it explicitly.
 * Validates and coerces field values against the object's metadata before persisting.
 */
public interface DynamicPersistenceService {

    /** Validates data against object metadata, then inserts a new record. */
    Mono<RecordEntity> createRecord(String objectApiName, Map<String, Object> data);

    /** Returns empty if the record does not exist or belongs to a different tenant. */
    Mono<RecordEntity> getRecord(UUID id);

    Flux<RecordEntity> listRecords(String objectApiName, PageRequest page);

    /** Partial update: only supplied keys are updated. No coercion in Phase 1. */
    Mono<RecordEntity> updateRecord(UUID id, Map<String, Object> dataPatch);

    Mono<Void> deleteRecord(UUID id);
}
