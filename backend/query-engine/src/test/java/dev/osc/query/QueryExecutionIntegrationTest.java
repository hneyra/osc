package dev.osc.query;

import dev.osc.metadata.FieldDefinition;
import dev.osc.metadata.MetadataEngine;
import dev.osc.metadata.MetadataRepository;
import dev.osc.metadata.ObjectDefinition;
import dev.osc.metadata.RelationshipDefinition;
import dev.osc.metadata.RecordTypeDefinition;
import dev.osc.metadata.LayoutAssignmentDefinition;
import dev.osc.persistence.R2dbcMetadataRepository;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration tests for the Query Engine executor — issue #24.
 *
 * <p>Exercises the full pipeline against a real PostgreSQL 16 (Testcontainers + the production
 * Flyway migrations, which seed the standard {@code Account} object): parse a SOQL-like string →
 * translate to parameterized SQL validated against metadata → execute via {@link R2dbcQueryExecutor}
 * and read back typed values. Verifies execution, empty results, {@code count()}, ordering and
 * pagination. Fully reactive; tests may {@code .block()}, production may not.</p>
 *
 * <p>Requires Docker — skipped automatically where Docker is unavailable.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("QueryExecutor end-to-end (parse -> translate -> execute on real PostgreSQL)")
class QueryExecutionIntegrationTest {

    private static final UUID SYSTEM_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @SuppressWarnings("resource")
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("osc_query_test")
                    .withUsername("osc")
                    .withPassword("test");

    private static DatabaseClient client;
    private static QueryParser parser;
    private static QueryTranslator translator;
    private static QueryExecutor executor;
    private static UUID accountObjectId;

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

        // Account is seeded by V2; fetch its object_id and insert a few records to query.
        accountObjectId = client.sql(
                        "SELECT id FROM md_object WHERE tenant_id = :t AND api_name = 'Account'")
                .bind("t", SYSTEM_TENANT)
                .map((row, meta) -> row.get("id", UUID.class))
                .one().block();

        insertAccount("Acme Corp", "Technology");
        insertAccount("Globex", "Finance");
        insertAccount("Initech", "Technology");
    }

    private static void insertAccount(String name, String industry) {
        client.sql("""
                        INSERT INTO record (tenant_id, object_id, name, data)
                        VALUES (:t, :o, :n, CAST(:d AS jsonb))
                        """)
                .bind("t", SYSTEM_TENANT)
                .bind("o", accountObjectId)
                .bind("n", name)
                .bind("d", "{\"industry__c\":\"" + industry + "\"}")
                .fetch().rowsUpdated().block();
    }

    private List<Map<String, Object>> run(String soql) {
        SelectQuery ast = parser.parse(soql);
        TranslatedQuery tq = translator.translate(ast, SYSTEM_TENANT, java.util.Set.of()).block();
        assertThat(tq).isNotNull();
        return executor.execute(tq).collectList().block();
    }

    @Test
    @DisplayName("executes a SELECT with WHERE and returns typed rows (COLUMN + JSONB fields)")
    void executesSelectWithWhere() {
        List<Map<String, Object>> rows =
                run("SELECT name, industry__c FROM Account WHERE name = 'Acme Corp'");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0))
                .containsEntry("name", "Acme Corp")            // COLUMN field
                .containsEntry("industry__c", "Technology");   // JSONB field data->>'industry__c'
    }

    @Test
    @DisplayName("IN clause returns all matching rows, ordered")
    void inClauseOrdered() {
        List<Map<String, Object>> rows =
                run("SELECT name FROM Account WHERE industry__c IN ('Technology') ORDER BY name ASC");

        assertThat(rows).extracting(r -> r.get("name"))
                .containsExactly("Acme Corp", "Initech");
    }

    @Test
    @DisplayName("LIMIT/OFFSET paginate the result set")
    void limitOffsetPaginates() {
        List<Map<String, Object>> page1 = run("SELECT name FROM Account ORDER BY name ASC LIMIT 2");
        List<Map<String, Object>> page2 = run("SELECT name FROM Account ORDER BY name ASC LIMIT 2 OFFSET 2");

        assertThat(page1).extracting(r -> r.get("name")).containsExactly("Acme Corp", "Globex");
        assertThat(page2).extracting(r -> r.get("name")).containsExactly("Initech");
    }

    @Test
    @DisplayName("no match returns an empty Flux, not an error")
    void noMatchIsEmpty() {
        SelectQuery ast = parser.parse("SELECT name FROM Account WHERE name = 'Nonexistent'");
        TranslatedQuery tq = translator.translate(ast, SYSTEM_TENANT, java.util.Set.of()).block();

        StepVerifier.create(executor.execute(tq)).verifyComplete();
    }

    @Test
    @DisplayName("count() returns the total number of matching rows")
    void countReturnsTotal() {
        SelectQuery ast = parser.parse("SELECT name FROM Account WHERE industry__c = 'Technology'");
        TranslatedQuery tq = translator.translate(ast, SYSTEM_TENANT, java.util.Set.of()).block();

        StepVerifier.create(executor.count(tq))
                .expectNext(2L)
                .verifyComplete();
    }

    @Test
    @DisplayName("FLS: a forbidden field is absent from the executed SELECT and the result rows")
    void flsStripsForbiddenField() {
        SelectQuery ast = parser.parse("SELECT name, industry__c FROM Account WHERE name = 'Acme Corp'");
        // allowedFields restricts the projection to 'name' only — 'industry__c' must be stripped.
        TranslatedQuery tq = translator.translate(ast, SYSTEM_TENANT, java.util.Set.of("name")).block();
        assertThat(tq).isNotNull();
        assertThat(tq.sql()).doesNotContain("industry__c");

        List<Map<String, Object>> rows = executor.execute(tq).collectList().block();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsKey("name").doesNotContainKey("industry__c");
    }

    @Test
    @DisplayName("Formula evaluation computes values correctly at read-time and handles FLS gracefully")
    void formulaEvaluationIntegration() {
        // Insert amount__c (NUMBER) and tax__c (NUMBER)
        client.sql("""
                INSERT INTO md_field (id, tenant_id, object_id, api_name, label, field_type, storage_kind, storage_key, is_required, is_custom)
                VALUES
                    ('00000000-0000-0000-0002-000000000001', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0001-000000000001',
                     'amount__c', 'Amount', 'NUMBER', 'JSONB', 'amount__c', FALSE, TRUE),
                    ('00000000-0000-0000-0002-000000000002', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0001-000000000001',
                     'tax_rate__c', 'Tax Rate', 'NUMBER', 'JSONB', 'tax_rate__c', FALSE, TRUE)
                ON CONFLICT DO NOTHING
                """).fetch().rowsUpdated().block();

        // Insert total_tax__c (FORMULA): amount__c * tax_rate__c
        client.sql("""
                INSERT INTO md_field (id, tenant_id, object_id, api_name, label, field_type, storage_kind, storage_key, is_required, is_custom, config)
                VALUES
                    ('00000000-0000-0000-0002-000000000003', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0001-000000000001',
                     'total_tax__c', 'Total Tax', 'FORMULA', 'JSONB', 'total_tax__c', FALSE, TRUE, CAST('{"formula":"amount__c * tax_rate__c"}' AS jsonb))
                ON CONFLICT DO NOTHING
                """).fetch().rowsUpdated().block();

        // Let's insert a record with amount__c = 100 and tax_rate__c = 0.15
        client.sql("""
                INSERT INTO record (id, tenant_id, object_id, name, data)
                VALUES
                    ('00000000-0000-0000-0003-000000000001', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0001-000000000001',
                     'Formula Account', CAST('{"amount__c":100, "tax_rate__c":0.15}' AS jsonb))
                ON CONFLICT DO NOTHING
                """).fetch().rowsUpdated().block();

        // Query the formula field and the dependency fields
        SelectQuery ast = parser.parse("SELECT name, amount__c, tax_rate__c, total_tax__c FROM Account WHERE name = 'Formula Account'");
        TranslatedQuery tq = translator.translate(ast, SYSTEM_TENANT, java.util.Set.of()).block();
        assertThat(tq).isNotNull();

        List<Map<String, Object>> rows = executor.execute(tq).collectList().block();
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("name")).isEqualTo("Formula Account");
        assertThat(row.get("total_tax__c")).isEqualTo(15.0);

        // FLS Protection Test: If 'amount__c' is NOT in allowedFields, 'total_tax__c' must evaluate gracefully
        // because the parser/evaluator falls back to null -> 0.0 * 0.15 = 0.0
        TranslatedQuery tqFls = translator.translate(ast, SYSTEM_TENANT, java.util.Set.of("name", "tax_rate__c", "total_tax__c")).block();
        assertThat(tqFls).isNotNull();
        assertThat(tqFls.sql()).doesNotContain("amount__c");

        List<Map<String, Object>> rowsFls = executor.execute(tqFls).collectList().block();
        assertThat(rowsFls).hasSize(1);
        Map<String, Object> rowFls = rowsFls.get(0);
        assertThat(rowFls).containsKey("total_tax__c");
        assertThat(rowFls.get("total_tax__c")).isEqualTo(0.0);
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

        @Override
        public Flux<RelationshipDefinition> getRelationships(UUID tenantId, UUID objectId) {
            return repository.findRelationships(tenantId, objectId);
        }

        @Override
        public Flux<RecordTypeDefinition> getRecordTypes(UUID tenantId, UUID objectId) {
            return repository.findRecordTypes(tenantId, objectId);
        }

        @Override
        public Mono<LayoutAssignmentDefinition> resolveLayoutAssignment(
                UUID tenantId, UUID objectId, UUID recordTypeId, UUID permissionSetId) {
            return repository.findLayoutAssignments(tenantId, objectId)
                    .collectList()
                    .flatMap(assignments -> {
                        java.util.Optional<LayoutAssignmentDefinition> p1 = assignments.stream()
                                .filter(a -> java.util.Objects.equals(a.recordTypeId(), recordTypeId)
                                        && java.util.Objects.equals(a.permissionSetId(), permissionSetId))
                                .findFirst();
                        if (p1.isPresent()) return Mono.just(p1.get());

                        java.util.Optional<LayoutAssignmentDefinition> p2 = assignments.stream()
                                .filter(a -> java.util.Objects.equals(a.recordTypeId(), recordTypeId)
                                        && a.permissionSetId() == null)
                                .findFirst();
                        if (p2.isPresent()) return Mono.just(p2.get());

                        java.util.Optional<LayoutAssignmentDefinition> p3 = assignments.stream()
                                .filter(a -> a.recordTypeId() == null
                                        && java.util.Objects.equals(a.permissionSetId(), permissionSetId))
                                .findFirst();
                        if (p3.isPresent()) return Mono.just(p3.get());

                        java.util.Optional<LayoutAssignmentDefinition> p4 = assignments.stream()
                                .filter(a -> a.recordTypeId() == null && a.permissionSetId() == null)
                                .findFirst();
                        return p4.map(Mono::just).orElseGet(Mono::empty);
                    });
        }
    }
}
