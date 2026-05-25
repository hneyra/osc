package dev.osc.persistence;

import dev.osc.metadata.ObjectDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for R2dbcMetadataRepository.
 * Requires Docker — skipped automatically in environments without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = R2dbcMetadataRepositoryIntegrationTest.TestConfig.class)
@DisplayName("R2dbcMetadataRepository integration")
class R2dbcMetadataRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("osc_test")
            .withUsername("osc")
            .withPassword("osc");

    @DynamicPropertySource
    static void r2dbcProperties(DynamicPropertyRegistry registry) {
        registry.add("r2dbc.url", () ->
                "r2dbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432)
                        + "/" + postgres.getDatabaseName());
    }

    @Configuration
    static class TestConfig {

        @Bean
        io.r2dbc.spi.ConnectionFactory connectionFactory(
                @org.springframework.beans.factory.annotation.Value("${r2dbc.url}") String url) {
            return io.r2dbc.spi.ConnectionFactories.get(
                    io.r2dbc.spi.ConnectionFactoryOptions.parse(url)
                            .mutate()
                            .option(io.r2dbc.spi.ConnectionFactoryOptions.USER, "osc")
                            .option(io.r2dbc.spi.ConnectionFactoryOptions.PASSWORD, "osc")
                            .build());
        }

        @Bean
        DatabaseClient databaseClient(io.r2dbc.spi.ConnectionFactory cf) {
            return DatabaseClient.create(cf);
        }

        @Bean
        R2dbcMetadataRepository r2dbcMetadataRepository(DatabaseClient databaseClient) {
            return new R2dbcMetadataRepository(databaseClient);
        }
    }

    @Autowired
    R2dbcMetadataRepository repository;

    @Autowired
    DatabaseClient client;

    @BeforeEach
    void createSchema() {
        client.sql("""
                CREATE TABLE IF NOT EXISTS tenant (
                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    api_name VARCHAR(255) NOT NULL UNIQUE,
                    display_name VARCHAR(255) NOT NULL,
                    is_active BOOLEAN NOT NULL DEFAULT TRUE,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                );
                CREATE TABLE IF NOT EXISTS md_object (
                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    tenant_id UUID NOT NULL REFERENCES tenant(id),
                    api_name VARCHAR(255) NOT NULL,
                    label VARCHAR(255) NOT NULL,
                    label_plural VARCHAR(255) NOT NULL,
                    is_custom BOOLEAN NOT NULL DEFAULT FALSE,
                    description TEXT,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    UNIQUE (tenant_id, api_name)
                );
                CREATE TABLE IF NOT EXISTS md_field (
                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    tenant_id UUID NOT NULL,
                    object_id UUID NOT NULL REFERENCES md_object(id) ON DELETE CASCADE,
                    api_name VARCHAR(255) NOT NULL,
                    label VARCHAR(255) NOT NULL,
                    field_type VARCHAR(50) NOT NULL,
                    storage_kind VARCHAR(20) NOT NULL DEFAULT 'JSONB',
                    storage_key VARCHAR(255),
                    is_required BOOLEAN NOT NULL DEFAULT FALSE,
                    is_custom BOOLEAN NOT NULL DEFAULT FALSE,
                    config JSONB,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """)
                .fetch().rowsUpdated().block();
    }

    UUID insertTenant() {
        return client.sql("INSERT INTO tenant (api_name, display_name) VALUES (:n, :d) RETURNING id")
                .bind("n", "t-" + UUID.randomUUID()).bind("d", "Test")
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

    @Test
    @DisplayName("findObject: returns the object definition for the correct tenant")
    void findObject_returnsDefinition() {
        UUID tenantId = insertTenant();
        insertObject(tenantId, "Account");

        StepVerifier.create(repository.findObject(tenantId, "Account"))
                .assertNext(obj -> {
                    assertThat(obj.apiName()).isEqualTo("Account");
                    assertThat(obj.tenantId()).isEqualTo(tenantId);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("findObject: returns empty for unknown object")
    void findObject_unknownReturnsEmpty() {
        UUID tenantId = insertTenant();
        StepVerifier.create(repository.findObject(tenantId, "NonExistent"))
                .verifyComplete();
    }

    @Test
    @DisplayName("tenant isolation: object from tenant A is NOT visible to tenant B")
    void tenantIsolation_objectNotLeakingAcrossTenants() {
        UUID tenantA = insertTenant();
        UUID tenantB = insertTenant();
        insertObject(tenantA, "SecretObject");

        StepVerifier.create(repository.findObject(tenantB, "SecretObject"))
                .verifyComplete();
    }

    @Test
    @DisplayName("findFields: returns fields in alphabetical order")
    void findFields_returnsFieldsOrdered() {
        UUID tenantId = insertTenant();
        UUID objectId = insertObject(tenantId, "WithFields");

        client.sql("""
                INSERT INTO md_field (tenant_id, object_id, api_name, label, field_type)
                VALUES (:tid, :oid, 'z_field', 'Z', 'TEXT'),
                       (:tid, :oid, 'a_field', 'A', 'NUMBER')
                """)
                .bind("tid", tenantId).bind("oid", objectId)
                .fetch().rowsUpdated().block();

        StepVerifier.create(repository.findFields(tenantId, objectId))
                .assertNext(f -> assertThat(f.apiName()).isEqualTo("a_field"))
                .assertNext(f -> assertThat(f.apiName()).isEqualTo("z_field"))
                .verifyComplete();
    }
}
