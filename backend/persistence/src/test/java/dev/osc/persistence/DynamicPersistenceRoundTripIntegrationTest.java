package dev.osc.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.osc.metadata.DefaultFieldCoercionEngine;
import dev.osc.metadata.FieldCoercionEngine;
import dev.osc.metadata.FieldDefinition;
import dev.osc.metadata.MetadataEngine;
import dev.osc.metadata.MetadataRepository;
import dev.osc.metadata.ObjectDefinition;
import dev.osc.metadata.TenantContext;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full insert round-trip for {@link DefaultDynamicPersistenceService} — issue #17.
 *
 * <p>Wires the real stack (metadata repository, coercion engine, record repository) against a
 * real PostgreSQL 16 with the production Flyway migrations, and verifies that API field values
 * flow through coercion into JSONB and can be read back. Also verifies that a missing required
 * field is rejected end-to-end.</p>
 *
 * <p>Uses plain TEXT fields so the assertions are independent of how NUMBER/DATE/etc. are
 * represented in JSONB (that is covered by the coercion-engine unit tests). Tenant id is supplied
 * exclusively through the Reactor Context. Requires Docker (Testcontainers).</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("DynamicPersistenceService round-trip (API values -> coercion -> JSONB -> read back)")
class DynamicPersistenceRoundTripIntegrationTest {

    @SuppressWarnings("resource")
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("osc_dps_test")
                    .withUsername("osc")
                    .withPassword("test");

    private static DatabaseClient client;
    private static DefaultDynamicPersistenceService service;

    private UUID tenant;

    @BeforeAll
    static void migrateAndWire() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();

        ConnectionFactory connectionFactory = ConnectionFactories.get(
                ConnectionFactoryOptions.builder()
                        .option(ConnectionFactoryOptions.DRIVER, "postgresql")
                        .option(ConnectionFactoryOptions.HOST, POSTGRES.getHost())
                        .option(ConnectionFactoryOptions.PORT, POSTGRES.getMappedPort(5432))
                        .option(ConnectionFactoryOptions.DATABASE, POSTGRES.getDatabaseName())
                        .option(ConnectionFactoryOptions.USER, POSTGRES.getUsername())
                        .option(ConnectionFactoryOptions.PASSWORD, POSTGRES.getPassword())
                        .build());
        client = DatabaseClient.create(connectionFactory);

        ObjectMapper mapper = new ObjectMapper();
        MetadataRepository metadataRepository = new R2dbcMetadataRepository(client);
        MetadataEngine metadataEngine = new DelegatingMetadataEngine(metadataRepository);
        FieldCoercionEngine coercionEngine = new DefaultFieldCoercionEngine();
        RecordRepository recordRepository = new R2dbcRecordRepository(client, mapper);
        service = new DefaultDynamicPersistenceService(metadataEngine, coercionEngine, recordRepository);
    }

    @BeforeEach
    void seedWidgetObject() {
        tenant = insertTenant();
        UUID widgetId = insertObject(tenant, "Widget");
        // name + color__c are optional; sku__c is required.
        insertField(tenant, widgetId, "name", "TEXT", "name", false);
        insertField(tenant, widgetId, "color__c", "TEXT", "color__c", false);
        insertField(tenant, widgetId, "sku__c", "TEXT", "sku__c", true);
    }

    @Test
    @DisplayName("create then get: supplied values are coerced, stored in JSONB and read back intact")
    void createThenGet_roundTrips() {
        RecordEntity created = service
                .createRecord("Widget", Map.of("name", "Widget One", "color__c", "red", "sku__c", "SKU-1"))
                .contextWrite(tenant(tenant))
                .block();

        assertThat(created).isNotNull();
        assertThat(created.data())
                .containsEntry("name", "Widget One")
                .containsEntry("color__c", "red")
                .containsEntry("sku__c", "SKU-1");

        StepVerifier.create(service.getRecord(created.id()).contextWrite(tenant(tenant)))
                .assertNext(read -> assertThat(read.data())
                        .containsEntry("name", "Widget One")
                        .containsEntry("color__c", "red")
                        .containsEntry("sku__c", "SKU-1"))
                .verifyComplete();
    }

    @Test
    @DisplayName("create: a missing required field is rejected before any row is written")
    void createMissingRequiredField_isRejected() {
        StepVerifier.create(
                service.createRecord("Widget", Map.of("name", "No Sku", "color__c", "blue"))
                        .contextWrite(tenant(tenant)))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(FieldValidationException.class);
                    assertThat(((FieldValidationException) err).getFieldApiName()).isEqualTo("sku__c");
                })
                .verify();
    }

    @Test
    @DisplayName("create: unknown object signals ObjectNotFoundException")
    void createUnknownObject_signalsError() {
        StepVerifier.create(
                service.createRecord("DoesNotExist", Map.of("name", "x"))
                        .contextWrite(tenant(tenant)))
                .expectError(ObjectNotFoundException.class)
                .verify();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Function<Context, Context> tenant(UUID tenantId) {
        return ctx -> ctx.put(TenantContext.TENANT_ID_KEY, tenantId.toString());
    }

    private UUID insertTenant() {
        return client.sql("INSERT INTO tenant (api_name, display_name) VALUES (:n, :d) RETURNING id")
                .bind("n", "t-" + UUID.randomUUID())
                .bind("d", "DPS IT Tenant")
                .map((row, meta) -> row.get("id", UUID.class))
                .one()
                .block();
    }

    private UUID insertObject(UUID tenantId, String apiName) {
        return client.sql("""
                        INSERT INTO md_object (tenant_id, api_name, label, label_plural)
                        VALUES (:tid, :n, :n, :np) RETURNING id
                        """)
                .bind("tid", tenantId)
                .bind("n", apiName)
                .bind("np", apiName + "s")
                .map((row, meta) -> row.get("id", UUID.class))
                .one()
                .block();
    }

    private void insertField(UUID tenantId, UUID objectId, String apiName,
                             String fieldType, String storageKey, boolean required) {
        client.sql("""
                        INSERT INTO md_field
                            (tenant_id, object_id, api_name, label, field_type, storage_kind, storage_key, is_required, is_custom)
                        VALUES (:tid, :oid, :n, :n, :ft, 'JSONB', :sk, :req, TRUE)
                        """)
                .bind("tid", tenantId)
                .bind("oid", objectId)
                .bind("n", apiName)
                .bind("ft", fieldType)
                .bind("sk", storageKey)
                .bind("req", required)
                .fetch()
                .rowsUpdated()
                .block();
    }

    /** Minimal MetadataEngine that delegates to the repository — no cache needed for this test. */
    private record DelegatingMetadataEngine(MetadataRepository repository) implements MetadataEngine {
        @Override
        public Mono<ObjectDefinition> findObject(UUID tenantId, String apiName) {
            return repository.findObject(tenantId, apiName);
        }

        @Override
        public Flux<FieldDefinition> findFields(UUID tenantId, UUID objectId) {
            return repository.findFields(tenantId, objectId);
        }

        @Override
        public Mono<Void> invalidate(UUID tenantId, String apiName) {
            return Mono.empty();
        }

        @Override
        public void recordFieldAccess(UUID tenantId, String objectApiName, String fieldApiName) {
            // no-op
        }
    }
}
