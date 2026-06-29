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

    // ── ADR-006: Extended Metadata ──────────────────────────────────────────

    /**
     * Returns all relationships where the given object participates (as child or parent).
     * Results are cached per (tenantId, objectId) and invalidated on write.
     */
    Flux<RelationshipDefinition> getRelationships(UUID tenantId, UUID objectId);

    /**
     * Returns all record types for the given object, default-first then alphabetical.
     * Results are cached per (tenantId, objectId) and invalidated on write.
     */
    Flux<RecordTypeDefinition> getRecordTypes(UUID tenantId, UUID objectId);

    /**
     * Resolves which layout to show for a record, implementing the most-specific-wins
     * priority order defined in {@code metadata-layout-assignment-schema.json}:
     * <ol>
     *   <li>(recordTypeId, permissionSetId) — both specified</li>
     *   <li>(recordTypeId, null) — record-type match, any profile</li>
     *   <li>(null, permissionSetId) — any record type, profile match</li>
     *   <li>(null, null) — object default</li>
     * </ol>
     * Returns empty if no assignment exists for this object.
     *
     * @param recordTypeId   the record type of the current record (may be null)
     * @param permissionSetId the caller's permission set (may be null)
     */
    Mono<LayoutAssignmentDefinition> resolveLayoutAssignment(
            UUID tenantId, UUID objectId, UUID recordTypeId, UUID permissionSetId);
}

