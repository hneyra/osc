package dev.osc.query;

import dev.osc.metadata.FieldDefinition;
import dev.osc.metadata.MetadataEngine;
import dev.osc.metadata.MetadataRepository;
import dev.osc.metadata.ObjectDefinition;
import dev.osc.persistence.R2dbcMetadataRepository;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SQL injection prevention for the Query Engine — <b>executor (with-DB) level</b> — issue #28.
 *
 * <p>The parser/translator-level vectors are covered without a DB by {@link QueryEngineInjectionTest}.
 * This suite closes the remaining acceptance item ("tests run ... with DB (executor level)") by
 * pushing injection payloads through the <em>full</em> pipeline (parse → translate → execute) against
 * a real PostgreSQL and proving that:</p>
 * <ul>
 *   <li>each payload is bound as a value and matches nothing — never executed;</li>
 *   <li>the schema and data survive intact (no DROP/TRUNCATE/INSERT side effects);</li>
 *   <li>the mandatory tenant filter ({@code $1}) cannot be bypassed — another tenant's row never leaks.</li>
 * </ul>
 *
 * <p>Requires Docker — skipped automatically where Docker is unavailable.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Query Engine SQL injection — executor level (real PostgreSQL)")
class QueryEngineInjectionIntegrationTest {

    private static final UUID SYSTEM_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @SuppressWarnings("resource")
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("osc_qinj_test")
                    .withUsername("osc")
                    .withPassword("test");

    private static DatabaseClient client;
    private static QueryParser parser;
    private static QueryTranslator translator;
    private static QueryExecutor executor;

    private static UUID otherTenant;

    @BeforeAll
    static void migrateWireAndSeed() {
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

        MetadataRepository metadataRepository = new R2dbcMetadataRepository(client);
        MetadataEngine metadataEngine = new DelegatingMetadataEngine(metadataRepository);
        parser = new DefaultQueryParser();
        translator = new DefaultQueryTranslator(metadataEngine);
        executor = new R2dbcQueryExecutor(client);

        UUID systemAccountId = objectId(SYSTEM_TENANT);
        insertRecord(SYSTEM_TENANT, systemAccountId, "Acme Corp");

        // A second tenant with its own Account object + record, to prove tenant isolation.
        otherTenant = client.sql(
                        "INSERT INTO tenant (api_name, display_name) VALUES (:n, 'Other') RETURNING id")
                .bind("n", "other-" + UUID.randomUUID())
                .map((row, meta) -> row.get("id", UUID.class)).one().block();
        UUID otherAccountId = client.sql("""
                        INSERT INTO md_object (tenant_id, api_name, label, label_plural)
                        VALUES (:t, 'Account', 'Account', 'Accounts') RETURNING id
                        """)
                .bind("t", otherTenant)
                .map((row, meta) -> row.get("id", UUID.class)).one().block();
        insertRecord(otherTenant, otherAccountId, "OtherTenantSecret");
    }

    private static UUID objectId(UUID tenant) {
        return client.sql("SELECT id FROM md_object WHERE tenant_id = :t AND api_name = 'Account'")
                .bind("t", tenant)
                .map((row, meta) -> row.get("id", UUID.class)).one().block();
    }

    private static void insertRecord(UUID tenant, UUID object, String name) {
        client.sql("INSERT INTO record (tenant_id, object_id, name, data) VALUES (:t, :o, :n, '{}'::jsonb)")
                .bind("t", tenant).bind("o", object).bind("n", name)
                .fetch().rowsUpdated().block();
    }

    private static long totalRecordCount() {
        return client.sql("SELECT count(*) FROM record")
                .map((row, meta) -> row.get(0, Long.class)).one().block();
    }

    @ParameterizedTest
    @DisplayName("injection payload as a WHERE value is bound, matches nothing, and never alters the schema/data")
    @ValueSource(strings = {
            // No internal single-quote: the scary tokens (;, --, DROP, UNION, pg_sleep) stay INSIDE
            // the string literal, so each payload actually reaches the executor as a positional bind.
            "1; DROP TABLE record",
            "1 OR 1=1",
            "1 UNION SELECT null, null",
            "100); DROP TABLE record; --",
            "x; SELECT pg_sleep(10); --",
            "1 AND (SELECT 1 FROM record LIMIT 1)=1",
            "1; TRUNCATE record",
            "1; INSERT INTO record (tenant_id) VALUES (gen_random_uuid())",
            // Quote-bearing payloads: the parser refuses these (a valid first line of defense).
            "' OR '1'='1",
            "'; DROP TABLE record; --"
    })
    void injectionValue_boundAndHarmlessAtExecutor(String injection) {
        long before = totalRecordCount();

        SelectQuery ast;
        try {
            ast = parser.parse("SELECT name FROM Account WHERE name = '" + injection + "'");
        } catch (ParseException ex) {
            // Parser refused the malformed input before any SQL — also a valid defense.
            assertThat(totalRecordCount())
                    .as("record table untouched after rejected parse of '%s'", injection)
                    .isEqualTo(before);
            return;
        }

        TranslatedQuery tq = translator.translate(ast, SYSTEM_TENANT, Set.of()).block();
        assertThat(tq).isNotNull();
        // The payload is a positional bind, not part of the SQL text.
        assertThat(tq.sql()).doesNotContain(injection);
        assertThat(tq.bindings()).contains(injection);

        List<Map<String, Object>> rows = executor.execute(tq).collectList().block();

        assertThat(rows).as("payload matched nothing — '%s'", injection).isEmpty();
        assertThat(totalRecordCount())
                .as("record table is untouched after '%s'", injection)
                .isEqualTo(before);
    }

    @Test
    @DisplayName("tenant filter cannot be bypassed: another tenant's row is invisible (system tenant query)")
    void tenantFilter_cannotBeBypassed() {
        // 'OtherTenantSecret' exists, but belongs to otherTenant — querying as the system tenant
        // must return nothing, because tenant_id = $1 is always injected.
        SelectQuery ast = parser.parse("SELECT name FROM Account WHERE name = 'OtherTenantSecret'");
        TranslatedQuery tq = translator.translate(ast, SYSTEM_TENANT, Set.of()).block();
        assertThat(tq).isNotNull();
        assertThat(tq.bindings().get(0)).isEqualTo(SYSTEM_TENANT);

        List<Map<String, Object>> rows = executor.execute(tq).collectList().block();
        assertThat(rows).isEmpty();
    }

    /** Minimal MetadataEngine delegating to the repository — no cache needed for this test. */
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
