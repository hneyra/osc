package dev.osc.metadata;

import dev.osc.metadata.performance.FieldAccessCounter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CaffeineMetadataEngine")
class CaffeineMetadataEngineTest {

    @Mock
    private MetadataRepository repository;

    private CaffeineMetadataEngine engine;

    @BeforeEach
    void setUp() {
        engine = new CaffeineMetadataEngine(repository, new FieldAccessCounter(), new MetadataCacheProperties());
    }

    // ── findObject ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("findObject")
    class FindObject {

        @Test
        @DisplayName("cache miss: delegates to repository and returns result")
        void cacheMiss_delegatesToRepository() {
            UUID tenantId = UUID.randomUUID();
            ObjectDefinition expected = objectFor(tenantId, "Account");
            when(repository.findObject(tenantId, "Account")).thenReturn(Mono.just(expected));

            StepVerifier.create(engine.findObject(tenantId, "Account"))
                    .expectNext(expected)
                    .verifyComplete();

            verify(repository, times(1)).findObject(tenantId, "Account");
        }

        @Test
        @DisplayName("cache hit: does not call repository a second time")
        void cacheHit_doesNotCallRepositoryAgain() {
            UUID tenantId = UUID.randomUUID();
            ObjectDefinition expected = objectFor(tenantId, "Account");
            when(repository.findObject(tenantId, "Account")).thenReturn(Mono.just(expected));

            // First call — cache miss
            engine.findObject(tenantId, "Account").block();
            // Second call — cache hit
            StepVerifier.create(engine.findObject(tenantId, "Account"))
                    .expectNext(expected)
                    .verifyComplete();

            verify(repository, times(1)).findObject(tenantId, "Account");
        }

        @Test
        @DisplayName("unknown object: returns empty without error")
        void unknownObject_returnsEmpty() {
            when(repository.findObject(any(), any())).thenReturn(Mono.empty());

            StepVerifier.create(engine.findObject(UUID.randomUUID(), "Unknown"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("tenant isolation: different tenants share no cache entries")
        void tenantIsolation_separateCacheKeys() {
            UUID tenantA = UUID.randomUUID();
            UUID tenantB = UUID.randomUUID();
            ObjectDefinition objA = objectFor(tenantA, "Account");
            ObjectDefinition objB = objectFor(tenantB, "Account");

            when(repository.findObject(tenantA, "Account")).thenReturn(Mono.just(objA));
            when(repository.findObject(tenantB, "Account")).thenReturn(Mono.just(objB));

            StepVerifier.create(engine.findObject(tenantA, "Account"))
                    .expectNext(objA).verifyComplete();

            StepVerifier.create(engine.findObject(tenantB, "Account"))
                    .expectNext(objB).verifyComplete();

            verify(repository).findObject(tenantA, "Account");
            verify(repository).findObject(tenantB, "Account");
        }
    }

    // ── invalidate ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("invalidate")
    class Invalidate {

        @Test
        @DisplayName("after invalidation, next findObject re-fetches from repository")
        void afterInvalidation_fetchesFromRepositoryAgain() {
            UUID tenantId = UUID.randomUUID();
            ObjectDefinition v1 = objectFor(tenantId, "Account");
            ObjectDefinition v2 = objectFor(tenantId, "Account");

            when(repository.findObject(tenantId, "Account"))
                    .thenReturn(Mono.just(v1))
                    .thenReturn(Mono.just(v2));

            // Populate cache
            engine.findObject(tenantId, "Account").block();
            // Invalidate and re-fetch
            engine.invalidate(tenantId, "Account").block();

            StepVerifier.create(engine.findObject(tenantId, "Account"))
                    .expectNext(v2)
                    .verifyComplete();

            verify(repository, times(2)).findObject(tenantId, "Account");
        }

        @Test
        @DisplayName("invalidation completes without error")
        void invalidation_completesCleanly() {
            StepVerifier.create(engine.invalidate(UUID.randomUUID(), "NonExistent"))
                    .verifyComplete();
        }
    }

    // ── findFields ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("findFields")
    class FindFields {

        @Test
        @DisplayName("delegates to repository and streams all fields")
        void delegatesToRepository() {
            UUID tenantId = UUID.randomUUID();
            UUID objectId = UUID.randomUUID();
            FieldDefinition f1 = fieldFor(tenantId, objectId, "name");
            FieldDefinition f2 = fieldFor(tenantId, objectId, "email__c");

            when(repository.findFields(tenantId, objectId)).thenReturn(Flux.just(f1, f2));

            StepVerifier.create(engine.findFields(tenantId, objectId))
                    .expectNext(f1, f2)
                    .verifyComplete();
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static ObjectDefinition objectFor(UUID tenantId, String apiName) {
        return new ObjectDefinition(
                UUID.randomUUID(), tenantId, apiName,
                apiName, apiName + "s", false, null, null);
    }

    private static FieldDefinition fieldFor(UUID tenantId, UUID objectId, String apiName) {
        return new FieldDefinition(
                UUID.randomUUID(), tenantId, objectId, apiName, apiName,
                FieldType.TEXT, StorageKind.JSONB, apiName,
                false, false, null, null, null);
    }
}
