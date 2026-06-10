package dev.osc.persistence;

import dev.osc.metadata.TenantContext;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tenant isolation integration tests — issue #20.
 *
 * <p>Verifies complete tenant isolation at both defence layers:</p>
 * <ul>
 *   <li><b>Application layer</b> — every repository query carries an explicit
 *       {@code WHERE tenant_id = :tenantId} filter resolved from Reactor Context:
 *       cross-tenant read returns empty, cross-tenant update returns {@code Mono.empty()},
 *       cross-tenant delete has no effect.</li>
 *   <li><b>PostgreSQL RLS layer</b> — the real Flyway migrations (V1+) are applied, and a
 *       dedicated NON-superuser role ({@value #PROBE_ROLE}) queries the tables directly
 *       with {@code app.current_tenant} set to the wrong tenant and WITHOUT any
 *       application-level tenant filter. RLS alone must return 0 rows. The container
 *       superuser cannot be used for this: superusers implicitly BYPASSRLS, which would
 *       make the assertion vacuous.</li>
 *   <li><b>Metadata layer</b> — object definitions of tenant A are invisible to tenant B,
 *       both through the metadata repository and through raw RLS-guarded SQL.</li>
 * </ul>
 *
 * Requires Docker — skipped automatically in environments without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = TenantIsolationIntegrationTest.TestConfig.class)
@DisplayName("Tenant Isolation")
class TenantIsolationIntegrationTest {

    /** Non-superuser role used to prove RLS without the superuser's implicit BYPASSRLS. */
    static final String PROBE_ROLE = "rls_probe";
    static final String PROBE_PASSWORD = "rls_probe_pw";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("osc_iso_test")
            .withUsername("osc")
            .withPassword("osc");

    static ConnectionFactory probeConnectionFactory;

    @DynamicPropertySource
    static void r2dbcProperties(DynamicPropertyRegistry registry) {
        registry.add("r2dbc.url", () ->
                "r2dbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432)
                        + "/" + postgres.getDatabaseName());
    }

    @BeforeAll
    static void migrateAndCreateProbeRole() throws SQLException {
        // Apply the real production migrations — including the RLS policies of V1.
        // (The previous version of this test created the tables by hand, which meant
        // RLS was never present in the test database and could not be verified.)
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();

        // The container user is a superuser and therefore implicitly bypasses RLS.
        // Create a plain role (no SUPERUSER, no BYPASSRLS, not table owner) that can
        // only be stopped by the row-level security policies themselves.
        try (var jdbc = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = jdbc.createStatement()) {
            statement.execute("CREATE ROLE " + PROBE_ROLE + " LOGIN PASSWORD '" + PROBE_PASSWORD + "'");
            statement.execute("GRANT SELECT ON record, md_object TO " + PROBE_ROLE);
        }

        probeConnectionFactory = ConnectionFactories.get(ConnectionFactoryOptions.builder()
                .option(ConnectionFactoryOptions.DRIVER, "postgresql")
                .option(ConnectionFactoryOptions.HOST, postgres.getHost())
                .option(ConnectionFactoryOptions.PORT, postgres.getMappedPort(5432))
                .option(ConnectionFactoryOptions.DATABASE, postgres.getDatabaseName())
                .option(ConnectionFactoryOptions.USER, PROBE_ROLE)
                .option(ConnectionFactoryOptions.PASSWORD, PROBE_PASSWORD)
                .build());
    }

    @Configuration
    static class TestConfig {

        @Bean
        ConnectionFactory connectionFactory(@Value("${r2dbc.url}") String url) {
            return ConnectionFactories.get(
                    ConnectionFactoryOptions.parse(url)
                            .mutate()
                            .option(ConnectionFactoryOptions.USER, "osc")
                            .option(ConnectionFactoryOptions.PASSWORD, "osc")
                            .build());
        }

        @Bean
        DatabaseClient databaseClient(ConnectionFactory cf) {
            return DatabaseClient.create(cf);
        }

        @Bean
        com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
            return new com.fasterxml.jackson.databind.ObjectMapper();
        }

        @Bean
        R2dbcRecordRepository r2dbcRecordRepository(DatabaseClient databaseClient,
                                                    com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
            return new R2dbcRecordRepository(databaseClient, objectMapper);
        }

        @Bean
        R2dbcMetadataRepository r2dbcMetadataRepository(DatabaseClient databaseClient) {
            return new R2dbcMetadataRepository(databaseClient);
        }
    }

    @Autowired
    R2dbcRecordRepository recordRepository;

    @Autowired
    R2dbcMetadataRepository metadataRepository;

    @Autowired
    DatabaseClient client;

    UUID tenantA;
    UUID tenantB;
    UUID objectIdA;

    @BeforeEach
    void setupTenants() {
        // Schema comes from the Flyway migrations (see @BeforeAll); each test only
        // needs its own fresh pair of tenants (api_names are unique per test).
        tenantA = insertTenant("A");
        tenantB = insertTenant("B");
        objectIdA = insertObject(tenantA, "Account");
    }

    UUID insertTenant(String suffix) {
        return client.sql("INSERT INTO tenant (api_name, display_name) VALUES (:n, :d) RETURNING id")
                .bind("n", "t" + suffix + "-" + UUID.randomUUID()).bind("d", "Tenant " + suffix)
                .map((row, meta) -> row.get("id", UUID.class)).one().block();
    }

    UUID insertObject(UUID tenantId, String apiName) {
        return client.sql("""
                INSERT INTO md_object (tenant_id, api_name, label, label_plural)
                VALUES (:tid, :n, :n, :np) RETURNING id
                """)
                .bind("tid", tenantId).bind("n", apiName).bind("np", apiName + "s")
                .map((row, meta) -> row.get("id", UUID.class)).one().block();
    }

    // ── Application layer ─────────────────────────────────────────────────────

    @Test
    @DisplayName("record inserted by tenant A is NOT visible to tenant B")
    void recordInsertedByTenantA_notVisibleToTenantB() {
        RecordEntity inserted = recordRepository
                .insert(new RecordInsertCommand(objectIdA, "A's Record", null, Map.of("secret", "A-data")))
                .contextWrite(ctx -> ctx.put(TenantContext.TENANT_ID_KEY, tenantA.toString()))
                .block();

        assertThat(inserted).isNotNull();

        StepVerifier.create(
                recordRepository.findById(inserted.id())
                        .contextWrite(ctx -> ctx.put(TenantContext.TENANT_ID_KEY, tenantB.toString()))
        )
                .verifyComplete();
    }

    @Test
    @DisplayName("listRecords returns only records belonging to the requesting tenant")
    void listRecords_tenantBSeesOnlyOwnRecords() {
        UUID objectIdB = insertObject(tenantB, "Account");

        recordRepository.insert(new RecordInsertCommand(objectIdA, "A's record", null, Map.of()))
                .contextWrite(ctx -> ctx.put(TenantContext.TENANT_ID_KEY, tenantA.toString()))
                .block();

        recordRepository.insert(new RecordInsertCommand(objectIdB, "B's record", null, Map.of()))
                .contextWrite(ctx -> ctx.put(TenantContext.TENANT_ID_KEY, tenantB.toString()))
                .block();

        StepVerifier.create(
                recordRepository.findByObjectId(objectIdB, PageRequest.DEFAULT)
                        .contextWrite(ctx -> ctx.put(TenantContext.TENANT_ID_KEY, tenantB.toString()))
        )
                .assertNext(r -> assertThat(r.tenantId()).isEqualTo(tenantB))
                .verifyComplete();
    }

    @Test
    @DisplayName("update by tenant B on a record owned by tenant A returns Mono.empty() and modifies nothing")
    void update_withWrongTenant_returnsEmptyAndModifiesNothing() {
        RecordEntity inserted = recordRepository
                .insert(new RecordInsertCommand(objectIdA, "A's Record", null, Map.of("secret", "A-data")))
                .contextWrite(ctx -> ctx.put(TenantContext.TENANT_ID_KEY, tenantA.toString()))
                .block();

        StepVerifier.create(
                recordRepository.update(new RecordUpdateCommand(
                                inserted.id(), "Hacked by B", null, Map.of("secret", "stolen")))
                        .contextWrite(ctx -> ctx.put(TenantContext.TENANT_ID_KEY, tenantB.toString()))
        )
                .verifyComplete();

        StepVerifier.create(
                recordRepository.findById(inserted.id())
                        .contextWrite(ctx -> ctx.put(TenantContext.TENANT_ID_KEY, tenantA.toString()))
        )
                .assertNext(r -> {
                    assertThat(r.name()).isEqualTo("A's Record");
                    assertThat(r.data()).containsEntry("secret", "A-data");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("delete by tenant B cannot delete a record owned by tenant A")
    void delete_cannotDeleteAnotherTenantsRecord() {
        RecordEntity inserted = recordRepository
                .insert(new RecordInsertCommand(objectIdA, "A's record", null, Map.of()))
                .contextWrite(ctx -> ctx.put(TenantContext.TENANT_ID_KEY, tenantA.toString()))
                .block();

        recordRepository.delete(inserted.id())
                .contextWrite(ctx -> ctx.put(TenantContext.TENANT_ID_KEY, tenantB.toString()))
                .block();

        StepVerifier.create(
                recordRepository.findById(inserted.id())
                        .contextWrite(ctx -> ctx.put(TenantContext.TENANT_ID_KEY, tenantA.toString()))
        )
                .assertNext(r -> assertThat(r.id()).isEqualTo(inserted.id()))
                .verifyComplete();
    }

    // ── PostgreSQL RLS layer (no application-level tenant filter) ─────────────

    @Test
    @DisplayName("RLS probe role is a genuine non-superuser without BYPASSRLS")
    void probeRole_cannotBypassRls() {
        // Guard: if this role were SUPERUSER or BYPASSRLS, the RLS tests below would
        // pass vacuously (PostgreSQL skips policies for such roles).
        Boolean canBypass = client.sql("""
                SELECT rolsuper OR rolbypassrls AS can_bypass
                FROM pg_roles WHERE rolname = :role
                """)
                .bind("role", PROBE_ROLE)
                .map((row, meta) -> row.get("can_bypass", Boolean.class))
                .one()
                .block();

        assertThat(canBypass).isFalse();
    }

    @Test
    @DisplayName("RLS alone blocks tenant B from reading tenant A's record — no WHERE tenant_id filter")
    void rls_blocksCrossTenantRecordSelect_withoutApplicationFilter() {
        RecordEntity inserted = recordRepository
                .insert(new RecordInsertCommand(objectIdA, "A's Record", null, Map.of("secret", "A-data")))
                .contextWrite(ctx -> ctx.put(TenantContext.TENANT_ID_KEY, tenantA.toString()))
                .block();

        // app.current_tenant = tenant B, querying by primary key only (the app-level
        // tenant filter is deliberately absent): RLS must hide the row.
        long visibleToB = countAsProbe(tenantB, "SELECT count(*) FROM record WHERE id = $1", inserted.id());
        assertThat(visibleToB).isZero();

        // Positive control: same query, same role, correct tenant — proves the zero
        // above comes from the RLS policy, not from a missing grant or a broken query.
        long visibleToA = countAsProbe(tenantA, "SELECT count(*) FROM record WHERE id = $1", inserted.id());
        assertThat(visibleToA).isEqualTo(1L);
    }

    // ── Metadata isolation ────────────────────────────────────────────────────

    @Test
    @DisplayName("metadata: tenant A's object definition is NOT visible to tenant B")
    void metadata_objectDefinitionNotVisibleToOtherTenant() {
        StepVerifier.create(metadataRepository.findObject(tenantB, "Account"))
                .verifyComplete();

        // Positive control: tenant A sees its own definition.
        StepVerifier.create(metadataRepository.findObject(tenantA, "Account"))
                .assertNext(obj -> {
                    assertThat(obj.tenantId()).isEqualTo(tenantA);
                    assertThat(obj.apiName()).isEqualTo("Account");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("metadata RLS: md_object row is hidden from tenant B on a direct non-superuser connection")
    void metadataRls_blocksCrossTenantObjectSelect_withoutApplicationFilter() {
        long visibleToB = countAsProbe(tenantB, "SELECT count(*) FROM md_object WHERE id = $1", objectIdA);
        assertThat(visibleToB).isZero();

        long visibleToA = countAsProbe(tenantA, "SELECT count(*) FROM md_object WHERE id = $1", objectIdA);
        assertThat(visibleToA).isEqualTo(1L);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Runs a count query on a single dedicated connection as the non-superuser probe
     * role, with {@code app.current_tenant} set to {@code activeTenant} beforehand.
     * set_config and the query MUST share one physical connection — the setting is
     * session-scoped — hence the raw Connection API instead of DatabaseClient.
     */
    long countAsProbe(UUID activeTenant, String countSql, UUID bindValue) {
        Long count = Mono.usingWhen(
                Mono.from(probeConnectionFactory.create()),
                conn -> setCurrentTenant(conn, activeTenant)
                        .then(Mono.from(conn.createStatement(countSql).bind("$1", bindValue).execute()))
                        .flatMapMany(result -> result.map((row, meta) -> row.get(0, Long.class)))
                        .single(),
                Connection::close)
                .block();
        assertThat(count).isNotNull();
        return count;
    }

    private static Mono<Void> setCurrentTenant(Connection conn, UUID tenantId) {
        return Mono.from(conn.createStatement("SELECT set_config('app.current_tenant', $1, false)")
                        .bind("$1", tenantId.toString())
                        .execute())
                .flatMapMany(result -> result.map((row, meta) -> row.get(0, String.class)))
                .then();
    }
}
