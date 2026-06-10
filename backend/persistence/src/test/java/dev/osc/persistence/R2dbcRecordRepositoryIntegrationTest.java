package dev.osc.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full CRUD integration tests for {@link R2dbcRecordRepository} — issue #16.
 *
 * <p>Runs against a real PostgreSQL 16 with the actual production Flyway migrations
 * (including the RLS policies on the {@code record} table). Covers the complete
 * round-trip of every repository operation plus cross-tenant isolation and
 * SQL-injection resistance through R2DBC binds.</p>
 *
 * <p>Tenant id is supplied exclusively through the Reactor Context — exactly as in
 * production — never as a method parameter. Tests may use {@code .block()};
 * production code may not.</p>
 *
 * <p>Requires Docker (Testcontainers); skipped automatically where Docker is unavailable.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("R2dbcRecordRepository CRUD integration (real PostgreSQL + Flyway)")
class R2dbcRecordRepositoryIntegrationTest {

    @SuppressWarnings("resource")
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("osc_record_test")
                    .withUsername("osc")
                    .withPassword("test");

    private static DatabaseClient client;
    private static R2dbcRecordRepository repository;

    private UUID tenantA;
    private UUID tenantB;
    private UUID objectIdA;

    @BeforeAll
    static void migrateAndWire() {
        // Apply the real production migrations — schema, indexes and RLS policies.
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
        repository = new R2dbcRecordRepository(client, new ObjectMapper());
    }

    @BeforeEach
    void seedTenantsAndObject() {
        tenantA = insertTenant();
        tenantB = insertTenant();
        objectIdA = insertObject(tenantA, "Account");
    }

    // ── insert ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("insert: returns the persisted row with id, tenant from context, data and timestamps")
    void insert_returnsPersistedRow() {
        RecordEntity inserted = repository
                .insert(new RecordInsertCommand(objectIdA, "Acme Corp", null,
                        Map.of("industry__c", "Software", "employees", 42)))
                .contextWrite(tenant(tenantA))
                .block();

        assertThat(inserted).isNotNull();
        assertThat(inserted.id()).isNotNull();
        assertThat(inserted.tenantId()).isEqualTo(tenantA); // from Reactor Context, not a parameter
        assertThat(inserted.objectId()).isEqualTo(objectIdA);
        assertThat(inserted.name()).isEqualTo("Acme Corp");
        assertThat(inserted.data())
                .containsEntry("industry__c", "Software")
                .containsEntry("employees", 42);
        assertThat(inserted.createdAt()).isNotNull();
        assertThat(inserted.updatedAt()).isNotNull();
    }

    // ── findById ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("findById: returns the record for the owning tenant, JSONB data round-trips")
    void findById_found() {
        RecordEntity inserted = insertRecord(tenantA, objectIdA, "Lookup Me",
                Map.of("website__c", "https://example.com"));

        StepVerifier.create(repository.findById(inserted.id()).contextWrite(tenant(tenantA)))
                .assertNext(found -> {
                    assertThat(found.id()).isEqualTo(inserted.id());
                    assertThat(found.name()).isEqualTo("Lookup Me");
                    assertThat(found.data()).containsEntry("website__c", "https://example.com");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("findById: unknown id completes empty — not an error")
    void findById_unknownIdIsEmpty() {
        StepVerifier.create(repository.findById(UUID.randomUUID()).contextWrite(tenant(tenantA)))
                .verifyComplete();
    }

    @Test
    @DisplayName("findById: cross-tenant access returns empty — record of tenant A invisible to tenant B")
    void findById_wrongTenantIsEmpty() {
        RecordEntity inserted = insertRecord(tenantA, objectIdA, "Secret", Map.of("k", "v"));

        StepVerifier.create(repository.findById(inserted.id()).contextWrite(tenant(tenantB)))
                .verifyComplete();
    }

    // ── findByObjectId ───────────────────────────────────────────────────────

    @Test
    @DisplayName("findByObjectId: pages through all records without overlap")
    void findByObjectId_paginates() throws InterruptedException {
        // Small gaps keep created_at strictly distinct so DESC paging is deterministic.
        RecordEntity r1 = insertRecord(tenantA, objectIdA, "r1", Map.of());
        Thread.sleep(5);
        RecordEntity r2 = insertRecord(tenantA, objectIdA, "r2", Map.of());
        Thread.sleep(5);
        RecordEntity r3 = insertRecord(tenantA, objectIdA, "r3", Map.of());

        List<RecordEntity> firstPage = repository
                .findByObjectId(objectIdA, new PageRequest(0, 2))
                .contextWrite(tenant(tenantA))
                .collectList()
                .block();
        List<RecordEntity> secondPage = repository
                .findByObjectId(objectIdA, new PageRequest(1, 2))
                .contextWrite(tenant(tenantA))
                .collectList()
                .block();

        // Newest first (created_at DESC): page 0 = [r3, r2], page 1 = [r1].
        assertThat(firstPage).extracting(RecordEntity::id).containsExactly(r3.id(), r2.id());
        assertThat(secondPage).extracting(RecordEntity::id).containsExactly(r1.id());
    }

    @Test
    @DisplayName("findByObjectId: empty object completes with no elements")
    void findByObjectId_noRecordsIsEmpty() {
        StepVerifier.create(
                repository.findByObjectId(objectIdA, PageRequest.DEFAULT).contextWrite(tenant(tenantA)))
                .verifyComplete();
    }

    // ── update ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("update: merges data patch, updates name, and bumps updated_at automatically")
    void update_appliesPatchAndBumpsUpdatedAt() throws InterruptedException {
        RecordEntity inserted = insertRecord(tenantA, objectIdA, "Before",
                Map.of("keep", "original", "change", "old"));

        Thread.sleep(10); // ensure now() advances past the insert timestamp

        RecordEntity updated = repository
                .update(new RecordUpdateCommand(inserted.id(), "After", null, Map.of("change", "new")))
                .contextWrite(tenant(tenantA))
                .block();

        assertThat(updated).isNotNull();
        assertThat(updated.name()).isEqualTo("After");
        assertThat(updated.data())
                .containsEntry("keep", "original") // untouched keys survive the patch
                .containsEntry("change", "new");
        assertThat(updated.createdAt()).isEqualTo(inserted.createdAt());
        assertThat(updated.updatedAt()).isAfter(inserted.updatedAt());
    }

    @Test
    @DisplayName("update: cross-tenant update completes empty and leaves the record untouched")
    void update_wrongTenantIsEmptyAndHarmless() {
        RecordEntity inserted = insertRecord(tenantA, objectIdA, "Mine", Map.of("k", "v"));

        StepVerifier.create(
                repository.update(new RecordUpdateCommand(inserted.id(), "Hijacked", null, Map.of()))
                        .contextWrite(tenant(tenantB)))
                .verifyComplete();

        RecordEntity still = repository.findById(inserted.id()).contextWrite(tenant(tenantA)).block();
        assertThat(still).isNotNull();
        assertThat(still.name()).isEqualTo("Mine");
    }

    // ── delete ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("delete: removes the row — subsequent findById is empty")
    void delete_removesRow() {
        RecordEntity inserted = insertRecord(tenantA, objectIdA, "Doomed", Map.of());

        StepVerifier.create(repository.delete(inserted.id()).contextWrite(tenant(tenantA)))
                .verifyComplete();

        StepVerifier.create(repository.findById(inserted.id()).contextWrite(tenant(tenantA)))
                .verifyComplete();
    }

    // ── SQL injection via binds ──────────────────────────────────────────────

    @Test
    @DisplayName("SQL injection: malicious payloads are bound as plain data — schema survives intact")
    void maliciousInput_isBoundAsData_notExecuted() {
        String malicious = "'; DROP TABLE record; --";

        RecordEntity inserted = repository
                .insert(new RecordInsertCommand(objectIdA, malicious, null, Map.of("payload", malicious)))
                .contextWrite(tenant(tenantA))
                .block();

        assertThat(inserted).isNotNull();
        assertThat(inserted.name()).isEqualTo(malicious);
        assertThat(inserted.data()).containsEntry("payload", malicious);

        // The record table still exists and is fully queryable — nothing was executed.
        StepVerifier.create(repository.findById(inserted.id()).contextWrite(tenant(tenantA)))
                .assertNext(found -> assertThat(found.name()).isEqualTo(malicious))
                .verifyComplete();
    }

    @Test
    @DisplayName("SQL injection: a malicious tenant id in the context fails fast before reaching SQL")
    void maliciousTenantId_failsBeforeSql() {
        StepVerifier.create(
                repository.findById(UUID.randomUUID())
                        .contextWrite(ctx -> ctx.put(TenantContext.TENANT_ID_KEY, "'; DROP TABLE record; --")))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static java.util.function.Function<Context, Context> tenant(UUID tenantId) {
        return ctx -> ctx.put(TenantContext.TENANT_ID_KEY, tenantId.toString());
    }

    private RecordEntity insertRecord(UUID tenantId, UUID objectId, String name, Map<String, Object> data) {
        RecordEntity inserted = repository
                .insert(new RecordInsertCommand(objectId, name, null, data))
                .contextWrite(tenant(tenantId))
                .block();
        assertThat(inserted).isNotNull();
        return inserted;
    }

    private UUID insertTenant() {
        return client.sql("INSERT INTO tenant (api_name, display_name) VALUES (:n, :d) RETURNING id")
                .bind("n", "t-" + UUID.randomUUID())
                .bind("d", "Record IT Tenant")
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
}
