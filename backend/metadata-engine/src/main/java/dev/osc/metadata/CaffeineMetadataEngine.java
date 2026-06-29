package dev.osc.metadata;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.osc.metadata.performance.FieldAccessCounter;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * MetadataEngine backed by Caffeine AsyncLoadingCaches.
 *
 * <p>Object definitions are cached with Optional wrapping to store negative lookups
 * (Caffeine does not permit null values). Relationship and record-type lists are cached
 * as immutable lists per (tenantId, objectId).
 *
 * <p>Layout assignment resolution is NOT separately cached — it executes a targeted
 * repository call and applies the most-specific-wins priority in-memory using the already-
 * cached layout assignments loaded per object.
 *
 * <p>All three caches (object, relationship, recordType) share the same TTL and size
 * from {@link MetadataCacheProperties}. Calling {@link #invalidate(UUID, String)} evicts
 * the object entry; relationship and record-type entries are evicted by objectId when the
 * object is first re-resolved.
 */
@Service
public class CaffeineMetadataEngine implements MetadataEngine {

    private final MetadataRepository repository;
    private final FieldAccessCounter fieldAccessCounter;

    private final AsyncLoadingCache<ObjectCacheKey, Optional<ObjectDefinition>> objectCache;
    private final AsyncLoadingCache<ObjectIdCacheKey, List<RelationshipDefinition>> relationshipCache;
    private final AsyncLoadingCache<ObjectIdCacheKey, List<RecordTypeDefinition>> recordTypeCache;
    private final AsyncLoadingCache<ObjectIdCacheKey, List<LayoutAssignmentDefinition>> layoutAssignmentCache;

    public CaffeineMetadataEngine(MetadataRepository repository,
                                  FieldAccessCounter fieldAccessCounter,
                                  MetadataCacheProperties cacheProperties) {
        this.repository = repository;
        this.fieldAccessCounter = fieldAccessCounter;

        this.objectCache = Caffeine.newBuilder()
                .maximumSize(cacheProperties.getMaximumSize())
                .expireAfterWrite(cacheProperties.getExpireAfterWrite())
                .buildAsync((key, executor) ->
                        repository.findObject(key.tenantId(), key.apiName())
                                .map(Optional::of)
                                .defaultIfEmpty(Optional.empty())
                                .toFuture());

        this.relationshipCache = Caffeine.newBuilder()
                .maximumSize(cacheProperties.getMaximumSize())
                .expireAfterWrite(cacheProperties.getExpireAfterWrite())
                .buildAsync((key, executor) ->
                        repository.findRelationships(key.tenantId(), key.objectId())
                                .collectList()
                                .toFuture());

        this.recordTypeCache = Caffeine.newBuilder()
                .maximumSize(cacheProperties.getMaximumSize())
                .expireAfterWrite(cacheProperties.getExpireAfterWrite())
                .buildAsync((key, executor) ->
                        repository.findRecordTypes(key.tenantId(), key.objectId())
                                .collectList()
                                .toFuture());

        this.layoutAssignmentCache = Caffeine.newBuilder()
                .maximumSize(cacheProperties.getMaximumSize())
                .expireAfterWrite(cacheProperties.getExpireAfterWrite())
                .buildAsync((key, executor) ->
                        repository.findLayoutAssignments(key.tenantId(), key.objectId())
                                .collectList()
                                .toFuture());
    }

    // ── Existing methods ─────────────────────────────────────────────────────

    @Override
    public Mono<ObjectDefinition> findObject(UUID tenantId, String apiName) {
        return Mono.fromCompletionStage(objectCache.get(new ObjectCacheKey(tenantId, apiName)))
                .flatMap(opt -> opt.map(Mono::just).orElseGet(Mono::empty));
    }

    @Override
    public Flux<FieldDefinition> findFields(UUID tenantId, UUID objectId) {
        return repository.findFields(tenantId, objectId);
    }

    /**
     * Evicts object, relationship, record-type, and layout-assignment cache entries
     * associated with the given object api name. Since relationship/record-type caches are
     * keyed by objectId (not apiName), we resolve the objectId first (from the object cache)
     * then evict. If the object is not in cache, we fetch it fresh from the repository.
     */
    @Override
    public Mono<Void> invalidate(UUID tenantId, String apiName) {
        objectCache.synchronous().invalidate(new ObjectCacheKey(tenantId, apiName));
        // Best-effort eviction of related entries keyed by objectId:
        // resolve objectId via a direct repository call (object cache was just evicted).
        return repository.findObject(tenantId, apiName)
                .doOnNext(obj -> {
                    ObjectIdCacheKey key = new ObjectIdCacheKey(tenantId, obj.id());
                    relationshipCache.synchronous().invalidate(key);
                    recordTypeCache.synchronous().invalidate(key);
                    layoutAssignmentCache.synchronous().invalidate(key);
                })
                .then();
    }

    @Override
    public void recordFieldAccess(UUID tenantId, String objectApiName, String fieldApiName) {
        fieldAccessCounter.record(tenantId, objectApiName, fieldApiName);
    }

    // ── ADR-006: Extended Metadata ───────────────────────────────────────────

    @Override
    public Flux<RelationshipDefinition> getRelationships(UUID tenantId, UUID objectId) {
        return Mono.fromCompletionStage(relationshipCache.get(new ObjectIdCacheKey(tenantId, objectId)))
                .flatMapMany(Flux::fromIterable);
    }

    @Override
    public Flux<RecordTypeDefinition> getRecordTypes(UUID tenantId, UUID objectId) {
        return Mono.fromCompletionStage(recordTypeCache.get(new ObjectIdCacheKey(tenantId, objectId)))
                .flatMapMany(Flux::fromIterable);
    }

    /**
     * Resolves the most-specific layout assignment for the given record type + permission set.
     *
     * <p>Priority (highest first):
     * <ol>
     *   <li>(recordTypeId, permissionSetId)</li>
     *   <li>(recordTypeId, null)</li>
     *   <li>(null, permissionSetId)</li>
     *   <li>(null, null) — object default</li>
     * </ol>
     */
    @Override
    public Mono<LayoutAssignmentDefinition> resolveLayoutAssignment(
            UUID tenantId, UUID objectId, UUID recordTypeId, UUID permissionSetId) {

        return Mono.fromCompletionStage(layoutAssignmentCache.get(new ObjectIdCacheKey(tenantId, objectId)))
                .flatMap(assignments -> {
                    // Priority 1: exact match on both dimensions
                    Optional<LayoutAssignmentDefinition> p1 = assignments.stream()
                            .filter(a -> Objects.equals(a.recordTypeId(), recordTypeId)
                                    && Objects.equals(a.permissionSetId(), permissionSetId))
                            .findFirst();
                    if (p1.isPresent()) return Mono.just(p1.get());

                    // Priority 2: record type match, any profile
                    Optional<LayoutAssignmentDefinition> p2 = assignments.stream()
                            .filter(a -> Objects.equals(a.recordTypeId(), recordTypeId)
                                    && a.permissionSetId() == null)
                            .findFirst();
                    if (p2.isPresent()) return Mono.just(p2.get());

                    // Priority 3: any record type, profile match
                    Optional<LayoutAssignmentDefinition> p3 = assignments.stream()
                            .filter(a -> a.recordTypeId() == null
                                    && Objects.equals(a.permissionSetId(), permissionSetId))
                            .findFirst();
                    if (p3.isPresent()) return Mono.just(p3.get());

                    // Priority 4: object default (both null)
                    Optional<LayoutAssignmentDefinition> p4 = assignments.stream()
                            .filter(a -> a.recordTypeId() == null && a.permissionSetId() == null)
                            .findFirst();
                    return p4.map(Mono::just).orElseGet(Mono::empty);
                });
    }

    // ── Cache key types ──────────────────────────────────────────────────────

    private record ObjectCacheKey(UUID tenantId, String apiName) {}

    private record ObjectIdCacheKey(UUID tenantId, UUID objectId) {}
}
