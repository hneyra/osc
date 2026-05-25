package dev.osc.persistence;

import dev.osc.metadata.TenantContext;
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
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tenant isolation integration tests — issue #20.
 * Verifies records inserted by tenant A are never visible to tenant B.
 * Requires Docker — skipped automatically in environments without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = TenantIsolationIntegrationTest.TestConfig.class)
@DisplayName("Tenant Isolation")
class TenantIsolationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("osc_iso_test")
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
                @Value("${r2dbc.url}") String url) {
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
        com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
            return new com.fasterxml.jackson.databind.ObjectMapper();
        }

        @Bean
        R2dbcRecordRepository r2dbcRecordRepository(DatabaseClient databaseClient,
                                                    com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
            return new R2dbcRecordRepository(databaseClient, objectMapper);
        }
    }

    @Autowired
    R2dbcRecordRepository recordRepository;

    @Autowired
    DatabaseClient client;

    UUID tenantA;
    UUID tenantB;
    UUID objectIdA;

    @BeforeEach
    void setupSchema() {
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
                CREATE TABLE IF NOT EXISTS record (
                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    tenant_id UUID NOT NULL,
                    object_id UUID NOT NULL REFERENCES md_object(id),
                    name VARCHAR(255),
                    owner_id UUID,
                    data JSONB NOT NULL DEFAULT '{}',
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """)
                .fetch().rowsUpdated().block();

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
}
