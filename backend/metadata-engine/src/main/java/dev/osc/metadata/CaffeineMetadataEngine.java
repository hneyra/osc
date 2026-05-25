package dev.osc.metadata;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * MetadataEngine backed by a Caffeine AsyncLoadingCache.
 *
 * Optional<ObjectDefinition> is used as the cache value so that
 * negative results ("object not found") are also cached without
 * storing null — Caffeine does not permit null values.
 *
 * Fields are not cached here; they are loaded fresh on every call.
 * A dedicated field-level cache can be added in Phase 1 if profiling shows the need.
 */
@Service
public class CaffeineMetadataEngine implements MetadataEngine {

    private final MetadataRepository repository;
    private final AsyncLoadingCache<ObjectCacheKey, Optional<ObjectDefinition>> objectCache;

    public CaffeineMetadataEngine(MetadataRepository repository) {
        this.repository = repository;
        this.objectCache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofMinutes(10))
                .buildAsync((key, executor) ->
                        repository.findObject(key.tenantId(), key.apiName())
                                .map(Optional::of)
                                .defaultIfEmpty(Optional.empty())
                                .toFuture());
    }

    @Override
    public Mono<ObjectDefinition> findObject(UUID tenantId, String apiName) {
        return Mono.fromCompletionStage(objectCache.get(new ObjectCacheKey(tenantId, apiName)))
                .flatMap(opt -> opt.map(Mono::just).orElseGet(Mono::empty));
    }

    @Override
    public Flux<FieldDefinition> findFields(UUID tenantId, UUID objectId) {
        return repository.findFields(tenantId, objectId);
    }

    @Override
    public Mono<Void> invalidate(UUID tenantId, String apiName) {
        objectCache.synchronous().invalidate(new ObjectCacheKey(tenantId, apiName));
        return Mono.empty();
    }

    private record ObjectCacheKey(UUID tenantId, String apiName) {}
}
