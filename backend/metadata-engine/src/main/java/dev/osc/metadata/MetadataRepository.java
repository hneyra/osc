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

    // ── ADR-006: Extended Metadata ──────────────────────────────────────────

    /** Returns all relationships where the given object is either child or parent. */
    Flux<RelationshipDefinition> findRelationships(UUID tenantId, UUID objectId);

    /** Returns all record types for the given object, default-first then alphabetical. */
    Flux<RecordTypeDefinition> findRecordTypes(UUID tenantId, UUID objectId);

    /**
     * Returns all layout assignments for layouts belonging to the given object.
     * Used internally by the engine to implement most-specific-wins resolution.
     */
    Flux<LayoutAssignmentDefinition> findLayoutAssignments(UUID tenantId, UUID objectId);
}
