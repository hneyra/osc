package dev.osc.api.record;

import dev.osc.metadata.TenantContext;
import dev.osc.persistence.*;
import dev.osc.query.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DynamicRecordController")
class DynamicRecordControllerTest {

    @Mock DynamicPersistenceService persistenceService;
    @Mock QueryParser queryParser;
    @Mock QueryTranslator queryTranslator;
    @Mock QueryExecutor queryExecutor;

    DynamicRecordController controller;

    final UUID tenantId = UUID.randomUUID();
    final UUID recordId = UUID.randomUUID();
    final UUID objectId = UUID.randomUUID();

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        controller = new DynamicRecordController(persistenceService, queryParser, queryTranslator, queryExecutor);
    }

    @Test
    @DisplayName("GET /{objectApiName} returns list of records in envelope")
    void listRecords_returnsEnvelope() {
        RecordEntity entity = entity(recordId, objectId, Map.of("name", "ACME"));
        when(persistenceService.listRecords(eq("Account"), any())).thenReturn(Flux.just(entity));

        StepVerifier.create(
                controller.listRecords("Account", 50, 0)
                        .contextWrite(ctx -> ctx.put(TenantContext.TENANT_ID_KEY, tenantId.toString()))
        )
                .assertNext(resp -> {
                    assertThat(resp.objectApiName()).isEqualTo("Account");
                    assertThat(resp.data()).hasSize(1);
                    assertThat(resp.data().get(0)).containsKey("id");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("GET /{objectApiName} with unknown object returns 404")
    void listRecords_unknownObject_404() {
        when(persistenceService.listRecords(eq("Unknown"), any()))
                .thenReturn(Flux.error(new ObjectNotFoundException("Unknown")));

        StepVerifier.create(
                controller.listRecords("Unknown", 50, 0)
                        .contextWrite(ctx -> ctx.put(TenantContext.TENANT_ID_KEY, tenantId.toString()))
        )
                .expectError(org.springframework.web.server.ResponseStatusException.class)
                .verify();
    }

    @Test
    @DisplayName("POST /{objectApiName} creates and returns record")
    void createRecord_returnsCreated() {
        RecordEntity entity = entity(recordId, objectId, Map.of("name", "ACME"));
        when(persistenceService.createRecord(eq("Account"), any())).thenReturn(Mono.just(entity));

        StepVerifier.create(
                controller.createRecord("Account", Map.of("name", "ACME"))
                        .contextWrite(ctx -> ctx.put(TenantContext.TENANT_ID_KEY, tenantId.toString()))
        )
                .assertNext(map -> {
                    assertThat(map).containsKey("id");
                    assertThat(map).containsEntry("name", "ACME");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("GET /{objectApiName}/{id} returns 200 when record exists")
    void getRecord_existing_returns200() {
        RecordEntity entity = entity(recordId, objectId, Map.of());
        when(persistenceService.getRecord(recordId)).thenReturn(Mono.just(entity));

        StepVerifier.create(
                controller.getRecord("Account", recordId)
                        .contextWrite(ctx -> ctx.put(TenantContext.TENANT_ID_KEY, tenantId.toString()))
        )
                .assertNext(resp -> assertThat(resp.getStatusCode().value()).isEqualTo(200))
                .verifyComplete();
    }

    @Test
    @DisplayName("GET /{objectApiName}/{id} returns 404 when record not found")
    void getRecord_notFound_returns404() {
        when(persistenceService.getRecord(recordId)).thenReturn(Mono.empty());

        StepVerifier.create(
                controller.getRecord("Account", recordId)
                        .contextWrite(ctx -> ctx.put(TenantContext.TENANT_ID_KEY, tenantId.toString()))
        )
                .assertNext(resp -> assertThat(resp.getStatusCode().value()).isEqualTo(404))
                .verifyComplete();
    }

    @Test
    @DisplayName("DELETE /{objectApiName}/{id} completes without error")
    void deleteRecord_completesCleanly() {
        when(persistenceService.deleteRecord(recordId)).thenReturn(Mono.empty());

        StepVerifier.create(
                controller.deleteRecord("Account", recordId)
                        .contextWrite(ctx -> ctx.put(TenantContext.TENANT_ID_KEY, tenantId.toString()))
        )
                .verifyComplete();
    }

    @Test
    @DisplayName("POST /query returns records matching SOQL-like query")
    void queryEndpoint_returnsMatchingRecords() {
        SelectQuery ast = new SelectQuery(List.of(), true, "Account",
                null, null, dev.osc.query.OrderDirection.ASC, 10, 0);
        TranslatedQuery translated = new TranslatedQuery(
                "SELECT ... FROM record WHERE tenant_id = $1",
                List.of(tenantId), List.of());

        when(queryParser.parse("SELECT * FROM Account LIMIT 10")).thenReturn(ast);
        when(queryTranslator.translate(eq(ast), eq(tenantId), any()))
                .thenReturn(Mono.just(translated));
        when(queryExecutor.execute(translated))
                .thenReturn(Flux.just(Map.of("name", "ACME")));
        when(queryExecutor.count(translated)).thenReturn(Mono.just(1L));

        StepVerifier.create(
                controller.query("Account",
                        new DynamicRecordController.QueryRequest("SELECT * FROM Account LIMIT 10"))
                        .contextWrite(ctx -> ctx.put(TenantContext.TENANT_ID_KEY, tenantId.toString()))
        )
                .assertNext(resp -> {
                    assertThat(resp.totalCount()).isEqualTo(1L);
                    assertThat(resp.data()).hasSize(1);
                })
                .verifyComplete();
    }

    private RecordEntity entity(UUID id, UUID objectId, Map<String, Object> data) {
        return new RecordEntity(id, tenantId, objectId, null, null, data, Instant.now(), Instant.now());
    }
}
