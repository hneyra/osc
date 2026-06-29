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

import java.time.Instant;
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

            verify(repository, times(3)).findObject(tenantId, "Account");
        }

        @Test
        @DisplayName("invalidation completes without error")
        void invalidation_completesCleanly() {
            when(repository.findObject(any(), any())).thenReturn(Mono.empty());
            StepVerifier.create(engine.invalidate(UUID.randomUUID(), "NonExistent"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("after invalidation, next getRelationships re-fetches from repository")
        void afterInvalidation_relationshipCacheEvicted() {
            UUID tenantId = UUID.randomUUID();
            UUID objectId = UUID.randomUUID();
            ObjectDefinition obj = new ObjectDefinition(objectId, tenantId, "Account",
                    "Account", "Accounts", false, null, null);
            RelationshipDefinition rel = relationshipFor(tenantId, objectId);

            when(repository.findObject(tenantId, "Account"))
                    .thenReturn(Mono.just(obj));
            when(repository.findRelationships(tenantId, objectId))
                    .thenReturn(Flux.just(rel))
                    .thenReturn(Flux.just(rel));

            // Populate relationship cache
            engine.getRelationships(tenantId, objectId).blockLast();
            // Invalidate
            engine.invalidate(tenantId, "Account").block();
            // Re-fetch — must hit repository again
            engine.getRelationships(tenantId, objectId).blockLast();

            verify(repository, times(2)).findRelationships(tenantId, objectId);
        }

        @Test
        @DisplayName("after invalidation, next getRecordTypes re-fetches from repository")
        void afterInvalidation_recordTypeCacheEvicted() {
            UUID tenantId = UUID.randomUUID();
            UUID objectId = UUID.randomUUID();
            ObjectDefinition obj = new ObjectDefinition(objectId, tenantId, "Account",
                    "Account", "Accounts", false, null, null);
            RecordTypeDefinition rt = recordTypeFor(tenantId, objectId);

            when(repository.findObject(tenantId, "Account"))
                    .thenReturn(Mono.just(obj));
            when(repository.findRecordTypes(tenantId, objectId))
                    .thenReturn(Flux.just(rt))
                    .thenReturn(Flux.just(rt));

            engine.getRecordTypes(tenantId, objectId).blockLast();
            engine.invalidate(tenantId, "Account").block();
            engine.getRecordTypes(tenantId, objectId).blockLast();

            verify(repository, times(2)).findRecordTypes(tenantId, objectId);
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

    // ── getRelationships ────────────────────────────────────────────────────

    @Nested
    @DisplayName("getRelationships")
    class GetRelationships {

        @Test
        @DisplayName("cache miss: delegates to repository and returns results")
        void cacheMiss_delegatesToRepository() {
            UUID tenantId = UUID.randomUUID();
            UUID objectId = UUID.randomUUID();
            RelationshipDefinition rel = relationshipFor(tenantId, objectId);
            when(repository.findRelationships(tenantId, objectId)).thenReturn(Flux.just(rel));

            StepVerifier.create(engine.getRelationships(tenantId, objectId))
                    .expectNext(rel)
                    .verifyComplete();

            verify(repository, times(1)).findRelationships(tenantId, objectId);
        }

        @Test
        @DisplayName("cache hit: does not call repository a second time")
        void cacheHit_doesNotCallRepositoryAgain() {
            UUID tenantId = UUID.randomUUID();
            UUID objectId = UUID.randomUUID();
            RelationshipDefinition rel = relationshipFor(tenantId, objectId);
            when(repository.findRelationships(tenantId, objectId)).thenReturn(Flux.just(rel));

            engine.getRelationships(tenantId, objectId).blockLast();
            StepVerifier.create(engine.getRelationships(tenantId, objectId))
                    .expectNext(rel)
                    .verifyComplete();

            verify(repository, times(1)).findRelationships(tenantId, objectId);
        }

        @Test
        @DisplayName("no relationships: returns empty Flux without error")
        void noRelationships_returnsEmpty() {
            UUID tenantId = UUID.randomUUID();
            UUID objectId = UUID.randomUUID();
            when(repository.findRelationships(tenantId, objectId)).thenReturn(Flux.empty());

            StepVerifier.create(engine.getRelationships(tenantId, objectId))
                    .verifyComplete();
        }

        @Test
        @DisplayName("tenant isolation: different tenants share no cache entries")
        void tenantIsolation_separateCacheKeys() {
            UUID tenantA = UUID.randomUUID();
            UUID tenantB = UUID.randomUUID();
            UUID objectId = UUID.randomUUID();
            RelationshipDefinition relA = relationshipFor(tenantA, objectId);
            RelationshipDefinition relB = relationshipFor(tenantB, objectId);

            when(repository.findRelationships(tenantA, objectId)).thenReturn(Flux.just(relA));
            when(repository.findRelationships(tenantB, objectId)).thenReturn(Flux.just(relB));

            StepVerifier.create(engine.getRelationships(tenantA, objectId))
                    .expectNext(relA).verifyComplete();
            StepVerifier.create(engine.getRelationships(tenantB, objectId))
                    .expectNext(relB).verifyComplete();

            verify(repository).findRelationships(tenantA, objectId);
            verify(repository).findRelationships(tenantB, objectId);
        }
    }

    // ── getRecordTypes ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("getRecordTypes")
    class GetRecordTypes {

        @Test
        @DisplayName("cache miss: delegates to repository and returns results")
        void cacheMiss_delegatesToRepository() {
            UUID tenantId = UUID.randomUUID();
            UUID objectId = UUID.randomUUID();
            RecordTypeDefinition rt = recordTypeFor(tenantId, objectId);
            when(repository.findRecordTypes(tenantId, objectId)).thenReturn(Flux.just(rt));

            StepVerifier.create(engine.getRecordTypes(tenantId, objectId))
                    .expectNext(rt)
                    .verifyComplete();

            verify(repository, times(1)).findRecordTypes(tenantId, objectId);
        }

        @Test
        @DisplayName("cache hit: does not call repository a second time")
        void cacheHit_doesNotCallRepositoryAgain() {
            UUID tenantId = UUID.randomUUID();
            UUID objectId = UUID.randomUUID();
            RecordTypeDefinition rt = recordTypeFor(tenantId, objectId);
            when(repository.findRecordTypes(tenantId, objectId)).thenReturn(Flux.just(rt));

            engine.getRecordTypes(tenantId, objectId).blockLast();
            StepVerifier.create(engine.getRecordTypes(tenantId, objectId))
                    .expectNext(rt)
                    .verifyComplete();

            verify(repository, times(1)).findRecordTypes(tenantId, objectId);
        }

        @Test
        @DisplayName("tenant isolation: different tenants share no cache entries")
        void tenantIsolation_separateCacheKeys() {
            UUID tenantA = UUID.randomUUID();
            UUID tenantB = UUID.randomUUID();
            UUID objectId = UUID.randomUUID();
            RecordTypeDefinition rtA = recordTypeFor(tenantA, objectId);
            RecordTypeDefinition rtB = recordTypeFor(tenantB, objectId);

            when(repository.findRecordTypes(tenantA, objectId)).thenReturn(Flux.just(rtA));
            when(repository.findRecordTypes(tenantB, objectId)).thenReturn(Flux.just(rtB));

            StepVerifier.create(engine.getRecordTypes(tenantA, objectId))
                    .expectNext(rtA).verifyComplete();
            StepVerifier.create(engine.getRecordTypes(tenantB, objectId))
                    .expectNext(rtB).verifyComplete();

            verify(repository).findRecordTypes(tenantA, objectId);
            verify(repository).findRecordTypes(tenantB, objectId);
        }
    }

    // ── resolveLayoutAssignment ─────────────────────────────────────────────

    @Nested
    @DisplayName("resolveLayoutAssignment")
    class ResolveLayoutAssignment {

        @Test
        @DisplayName("priority 1: (recordTypeId, permissionSetId) — exact match wins")
        void priority1_exactMatch() {
            UUID tenantId = UUID.randomUUID();
            UUID objectId = UUID.randomUUID();
            UUID rtId = UUID.randomUUID();
            UUID psId = UUID.randomUUID();
            UUID layoutId = UUID.randomUUID();

            // Exact match (p1) + fallback default (p4) — p1 must win
            LayoutAssignmentDefinition p1 = layoutAssignment(tenantId, layoutId, rtId, psId);
            LayoutAssignmentDefinition p4 = layoutAssignment(tenantId, UUID.randomUUID(), null, null);

            when(repository.findLayoutAssignments(tenantId, objectId))
                    .thenReturn(Flux.just(p4, p1));

            StepVerifier.create(engine.resolveLayoutAssignment(tenantId, objectId, rtId, psId))
                    .expectNext(p1)
                    .verifyComplete();
        }

        @Test
        @DisplayName("priority 2: (recordTypeId, null) — record-type match beats profile-only")
        void priority2_recordTypeMatch() {
            UUID tenantId = UUID.randomUUID();
            UUID objectId = UUID.randomUUID();
            UUID rtId = UUID.randomUUID();
            UUID psId = UUID.randomUUID();

            LayoutAssignmentDefinition p2 = layoutAssignment(tenantId, UUID.randomUUID(), rtId, null);
            LayoutAssignmentDefinition p3 = layoutAssignment(tenantId, UUID.randomUUID(), null, psId);
            LayoutAssignmentDefinition p4 = layoutAssignment(tenantId, UUID.randomUUID(), null, null);

            when(repository.findLayoutAssignments(tenantId, objectId))
                    .thenReturn(Flux.just(p4, p3, p2));

            StepVerifier.create(engine.resolveLayoutAssignment(tenantId, objectId, rtId, psId))
                    .expectNext(p2)
                    .verifyComplete();
        }

        @Test
        @DisplayName("priority 3: (null, permissionSetId) — profile match beats object default")
        void priority3_profileMatch() {
            UUID tenantId = UUID.randomUUID();
            UUID objectId = UUID.randomUUID();
            UUID rtId = UUID.randomUUID();
            UUID psId = UUID.randomUUID();

            LayoutAssignmentDefinition p3 = layoutAssignment(tenantId, UUID.randomUUID(), null, psId);
            LayoutAssignmentDefinition p4 = layoutAssignment(tenantId, UUID.randomUUID(), null, null);

            when(repository.findLayoutAssignments(tenantId, objectId))
                    .thenReturn(Flux.just(p4, p3));

            StepVerifier.create(engine.resolveLayoutAssignment(tenantId, objectId, rtId, psId))
                    .expectNext(p3)
                    .verifyComplete();
        }

        @Test
        @DisplayName("priority 4: (null, null) — object default when no other match")
        void priority4_objectDefault() {
            UUID tenantId = UUID.randomUUID();
            UUID objectId = UUID.randomUUID();
            LayoutAssignmentDefinition p4 = layoutAssignment(tenantId, UUID.randomUUID(), null, null);

            when(repository.findLayoutAssignments(tenantId, objectId))
                    .thenReturn(Flux.just(p4));

            StepVerifier.create(engine.resolveLayoutAssignment(tenantId, objectId,
                    UUID.randomUUID(), UUID.randomUUID()))
                    .expectNext(p4)
                    .verifyComplete();
        }

        @Test
        @DisplayName("no match: returns empty Mono")
        void noMatch_returnsEmpty() {
            UUID tenantId = UUID.randomUUID();
            UUID objectId = UUID.randomUUID();

            when(repository.findLayoutAssignments(tenantId, objectId))
                    .thenReturn(Flux.empty());

            StepVerifier.create(engine.resolveLayoutAssignment(tenantId, objectId,
                    UUID.randomUUID(), UUID.randomUUID()))
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

    private static RelationshipDefinition relationshipFor(UUID tenantId, UUID objectId) {
        return new RelationshipDefinition(
                UUID.randomUUID(), tenantId, objectId, UUID.randomUUID(),
                "LOOKUP", UUID.randomUUID(), null, "RESTRICT", Instant.now());
    }

    private static RecordTypeDefinition recordTypeFor(UUID tenantId, UUID objectId) {
        return new RecordTypeDefinition(
                UUID.randomUUID(), tenantId, objectId,
                "Default", "Default", true, true, Instant.now());
    }

    private static LayoutAssignmentDefinition layoutAssignment(
            UUID tenantId, UUID layoutId, UUID recordTypeId, UUID permissionSetId) {
        return new LayoutAssignmentDefinition(
                UUID.randomUUID(), tenantId, layoutId, recordTypeId, permissionSetId, Instant.now());
    }
}
