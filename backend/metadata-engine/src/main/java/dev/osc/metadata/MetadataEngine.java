package dev.osc.metadata;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Primary port — reactive access to object and field definitions with caching.
 * All methods are non-blocking; never call .block() on any returned publisher.
 */
public interface MetadataEngine {

    /** Returns the ObjectDefinition for the given tenant+apiName, or empty if not found. */
    Mono<ObjectDefinition> findObject(UUID tenantId, String apiName);

    /** Returns all fields belonging to the given object. */
    Flux<FieldDefinition> findFields(UUID tenantId, UUID objectId);

    /** Evicts the cached entry so the next read re-fetches from the repository. */
    Mono<Void> invalidate(UUID tenantId, String apiName);

    /**
     * Records a field access event for hot-field promotion analysis.
     * Implementations may delegate to an in-memory counter or other store.
     */
    void recordFieldAccess(UUID tenantId, String objectApiName, String fieldApiName);
}
