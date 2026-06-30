package dev.osc.persistence;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Secondary port — reactive CRUD for the universal record table.
 * All methods read tenant_id from Reactor Context automatically.
 * Never pass tenant_id as a parameter from outside this layer.
 */
public interface RecordRepository {

    Mono<RecordEntity> insert(RecordInsertCommand cmd);

    /** Returns empty if the record does not exist or belongs to a different tenant. */
    Mono<RecordEntity> findById(UUID id);

    Flux<RecordEntity> findByObjectId(UUID objectId, PageRequest page);

    /** Finds all records of a specific object type that have a matching value for a field key in the data JSONB. */
    Flux<RecordEntity> findByField(UUID objectId, String fieldKey, Object value);

    Mono<RecordEntity> update(RecordUpdateCommand cmd);

    /** Returns empty if the record does not exist or belongs to a different tenant. */
    Mono<Void> delete(UUID id);
}
