package dev.osc.persistence;

import dev.osc.metadata.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultDynamicPersistenceService")
class DefaultDynamicPersistenceServiceTest {

    @Mock MetadataEngine metadataEngine;
    @Mock FieldCoercionEngine coercionEngine;
    @Mock RecordRepository recordRepository;

    DefaultDynamicPersistenceService service;

    final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultDynamicPersistenceService(metadataEngine, coercionEngine, recordRepository);
    }

    // ── createRecord ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("createRecord")
    class CreateRecord {

        @Test
        @DisplayName("valid data: coerces fields and inserts record")
        void validData_coercesAndInserts() {
            UUID objectId = UUID.randomUUID();
            UUID recordId = UUID.randomUUID();
            ObjectDefinition object = objectFor(tenantId, objectId, "Account");
            FieldDefinition nameField = fieldFor(tenantId, objectId, "name", FieldType.TEXT, StorageKind.JSONB, "name");
            RecordEntity saved = entityFor(recordId, tenantId, objectId, Map.of("name", "ACME"));

            when(metadataEngine.findObject(tenantId, "Account")).thenReturn(Mono.just(object));
            when(metadataEngine.findFields(tenantId, objectId)).thenReturn(Flux.just(nameField));
            when(coercionEngine.coerce(nameField, "ACME")).thenReturn(CoercionResult.success("ACME"));
            when(recordRepository.insert(any())).thenReturn(Mono.just(saved));

            StepVerifier.create(
                    service.createRecord("Account", Map.of("name", "ACME"))
                            .contextWrite(ctx -> ctx.put(TenantContext.TENANT_ID_KEY, tenantId.toString()))
            )
                    .expectNext(saved)
                    .verifyComplete();

            ArgumentCaptor<RecordInsertCommand> captor = ArgumentCaptor.forClass(RecordInsertCommand.class);
            verify(recordRepository).insert(captor.capture());
            assertThat(captor.getValue().objectId()).isEqualTo(objectId);
            assertThat(captor.getValue().data()).containsEntry("name", "ACME");
        }

        @Test
        @DisplayName("unknown object: signals ObjectNotFoundException")
        void unknownObject_signalsObjectNotFound() {
            when(metadataEngine.findObject(tenantId, "Unknown")).thenReturn(Mono.empty());

            StepVerifier.create(
                    service.createRecord("Unknown", Map.of())
                            .contextWrite(ctx -> ctx.put(TenantContext.TENANT_ID_KEY, tenantId.toString()))
            )
                    .expectError(ObjectNotFoundException.class)
                    .verify();

            verify(recordRepository, never()).insert(any());
        }

        @Test
        @DisplayName("coercion failure: signals FieldValidationException with field name")
        void coercionFailure_signalsFieldValidationException() {
            UUID objectId = UUID.randomUUID();
            ObjectDefinition object = objectFor(tenantId, objectId, "Account");
            FieldDefinition ageField = fieldFor(tenantId, objectId, "age__c", FieldType.NUMBER, StorageKind.JSONB, "age__c");

            when(metadataEngine.findObject(tenantId, "Account")).thenReturn(Mono.just(object));
            when(metadataEngine.findFields(tenantId, objectId)).thenReturn(Flux.just(ageField));
            when(coercionEngine.coerce(ageField, "not-a-number"))
                    .thenReturn(CoercionResult.failure("not a valid number"));

            StepVerifier.create(
                    service.createRecord("Account", Map.of("age__c", "not-a-number"))
                            .contextWrite(ctx -> ctx.put(TenantContext.TENANT_ID_KEY, tenantId.toString()))
            )
                    .expectErrorSatisfies(err -> {
                        assertThat(err).isInstanceOf(FieldValidationException.class);
                        assertThat(((FieldValidationException) err).getFieldApiName()).isEqualTo("age__c");
                    })
                    .verify();

            verify(recordRepository, never()).insert(any());
        }

        @Test
        @DisplayName("fields not in metadata are ignored and not persisted")
        void unknownFields_areIgnored() {
            UUID objectId = UUID.randomUUID();
            ObjectDefinition object = objectFor(tenantId, objectId, "Account");
            FieldDefinition nameField = fieldFor(tenantId, objectId, "name", FieldType.TEXT, StorageKind.JSONB, "name");
            RecordEntity saved = entityFor(UUID.randomUUID(), tenantId, objectId, Map.of("name", "ACME"));

            when(metadataEngine.findObject(tenantId, "Account")).thenReturn(Mono.just(object));
            when(metadataEngine.findFields(tenantId, objectId)).thenReturn(Flux.just(nameField));
            when(coercionEngine.coerce(nameField, "ACME")).thenReturn(CoercionResult.success("ACME"));
            when(recordRepository.insert(any())).thenReturn(Mono.just(saved));

            StepVerifier.create(
                    service.createRecord("Account", Map.of("name", "ACME", "unknown_field", "ignored"))
                            .contextWrite(ctx -> ctx.put(TenantContext.TENANT_ID_KEY, tenantId.toString()))
            )
                    .expectNext(saved)
                    .verifyComplete();

            ArgumentCaptor<RecordInsertCommand> captor = ArgumentCaptor.forClass(RecordInsertCommand.class);
            verify(recordRepository).insert(captor.capture());
            assertThat(captor.getValue().data()).doesNotContainKey("unknown_field");
        }
    }

    // ── getRecord ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getRecord")
    class GetRecord {

        @Test
        @DisplayName("existing record: returns the entity")
        void existingRecord_returnsEntity() {
            UUID id = UUID.randomUUID();
            RecordEntity entity = entityFor(id, tenantId, UUID.randomUUID(), Map.of("x", 1));
            when(recordRepository.findById(id)).thenReturn(Mono.just(entity));

            StepVerifier.create(
                    service.getRecord(id)
                            .contextWrite(ctx -> ctx.put(TenantContext.TENANT_ID_KEY, tenantId.toString()))
            )
                    .expectNext(entity)
                    .verifyComplete();
        }

        @Test
        @DisplayName("non-existent record: returns empty Mono")
        void nonExistent_returnsEmpty() {
            UUID id = UUID.randomUUID();
            when(recordRepository.findById(id)).thenReturn(Mono.empty());

            StepVerifier.create(
                    service.getRecord(id)
                            .contextWrite(ctx -> ctx.put(TenantContext.TENANT_ID_KEY, tenantId.toString()))
            )
                    .verifyComplete();
        }
    }

    // ── listRecords ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("listRecords")
    class ListRecords {

        @Test
        @DisplayName("existing object: streams records from repository")
        void existingObject_streamsRecords() {
            UUID objectId = UUID.randomUUID();
            ObjectDefinition object = objectFor(tenantId, objectId, "Contact");
            RecordEntity r1 = entityFor(UUID.randomUUID(), tenantId, objectId, Map.of());
            RecordEntity r2 = entityFor(UUID.randomUUID(), tenantId, objectId, Map.of());

            when(metadataEngine.findObject(tenantId, "Contact")).thenReturn(Mono.just(object));
            when(recordRepository.findByObjectId(objectId, PageRequest.DEFAULT))
                    .thenReturn(Flux.just(r1, r2));

            StepVerifier.create(
                    service.listRecords("Contact", PageRequest.DEFAULT)
                            .contextWrite(ctx -> ctx.put(TenantContext.TENANT_ID_KEY, tenantId.toString()))
            )
                    .expectNext(r1, r2)
                    .verifyComplete();
        }

        @Test
        @DisplayName("unknown object: signals ObjectNotFoundException")
        void unknownObject_signalsError() {
            when(metadataEngine.findObject(tenantId, "Unknown")).thenReturn(Mono.empty());

            StepVerifier.create(
                    service.listRecords("Unknown", PageRequest.DEFAULT)
                            .contextWrite(ctx -> ctx.put(TenantContext.TENANT_ID_KEY, tenantId.toString()))
            )
                    .expectError(ObjectNotFoundException.class)
                    .verify();
        }
    }

    // ── updateRecord ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("updateRecord")
    class UpdateRecord {

        @Test
        @DisplayName("delegates to recordRepository.update with correct command")
        void delegatesToRepository() {
            UUID id = UUID.randomUUID();
            Map<String, Object> patch = Map.of("name", "Updated");
            RecordEntity updated = entityFor(id, tenantId, UUID.randomUUID(), patch);

            when(recordRepository.update(any())).thenReturn(Mono.just(updated));

            StepVerifier.create(
                    service.updateRecord(id, patch)
                            .contextWrite(ctx -> ctx.put(TenantContext.TENANT_ID_KEY, tenantId.toString()))
            )
                    .expectNext(updated)
                    .verifyComplete();

            ArgumentCaptor<RecordUpdateCommand> captor = ArgumentCaptor.forClass(RecordUpdateCommand.class);
            verify(recordRepository).update(captor.capture());
            assertThat(captor.getValue().id()).isEqualTo(id);
            assertThat(captor.getValue().dataPatch()).isEqualTo(patch);
        }
    }

    // ── deleteRecord ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("deleteRecord")
    class DeleteRecord {

        @Test
        @DisplayName("delegates to recordRepository.delete")
        void delegatesToRepository() {
            UUID id = UUID.randomUUID();
            when(recordRepository.delete(id)).thenReturn(Mono.empty());

            StepVerifier.create(
                    service.deleteRecord(id)
                            .contextWrite(ctx -> ctx.put(TenantContext.TENANT_ID_KEY, tenantId.toString()))
            )
                    .verifyComplete();

            verify(recordRepository).delete(id);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static ObjectDefinition objectFor(UUID tenantId, UUID id, String apiName) {
        return new ObjectDefinition(id, tenantId, apiName, apiName, apiName + "s",
                false, Instant.now(), Instant.now());
    }

    private static FieldDefinition fieldFor(UUID tenantId, UUID objectId, String apiName,
                                             FieldType type, StorageKind kind, String storageKey) {
        return new FieldDefinition(UUID.randomUUID(), tenantId, objectId, apiName, apiName,
                type, kind, storageKey, false, false, null, Instant.now(), Instant.now());
    }

    private static RecordEntity entityFor(UUID id, UUID tenantId, UUID objectId,
                                           Map<String, Object> data) {
        return new RecordEntity(id, tenantId, objectId, null, null, data,
                Instant.now(), Instant.now());
    }
}
