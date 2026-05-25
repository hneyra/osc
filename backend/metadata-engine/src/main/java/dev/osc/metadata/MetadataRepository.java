package dev.osc.metadata;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Secondary port — defines what the metadata engine needs from the storage layer.
 * Implementations live in the persistence module (R2DBC adapter).
 */
public interface MetadataRepository {

    Mono<ObjectDefinition> findObject(UUID tenantId, String apiName);

    Flux<FieldDefinition> findFields(UUID tenantId, UUID objectId);
}
