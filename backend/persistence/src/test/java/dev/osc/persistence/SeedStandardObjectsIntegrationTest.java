package dev.osc.persistence;

import dev.osc.metadata.FieldDefinition;
import dev.osc.metadata.FieldType;
import dev.osc.metadata.ObjectDefinition;
import dev.osc.metadata.StorageKind;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the V2 seed (Account, Contact standard objects) is loadable through the reactive
 * {@link R2dbcMetadataRepository} — i.e. the same path the MetadataEngine uses at runtime —
 * with every field discoverable at its correct type and storage kind.
 *
 * <p>This complements {@link FlywayMigrationV1Test}, which checks the seed via raw JDBC.
 * Requires Docker (Testcontainers); skipped automatically where Docker is unavailable.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("V2 seed — Account & Contact queryable via the reactive metadata repository")
class SeedStandardObjectsIntegrationTest {

    private static final UUID SYSTEM_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @SuppressWarnings("resource")
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("osc_test")
                    .withUsername("osc")
                    .withPassword("test");

    private static R2dbcMetadataRepository repository;

    @BeforeAll
    static void migrateAndWire() {
        // Apply the real production migrations, including V2 seed data.
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
        repository = new R2dbcMetadataRepository(DatabaseClient.create(connectionFactory));
    }

    @AfterAll
    static void cleanup() {
        repository = null;
    }

    @Test
    @DisplayName("Account is loadable with all four seeded fields at the correct types/storage")
    void accountSeedIsQueryable() {
        ObjectDefinition account = repository.findObject(SYSTEM_TENANT, "Account").block();
        assertThat(account).isNotNull();
        assertThat(account.label()).isEqualTo("Account");
        assertThat(account.isCustom()).isFalse();

        List<FieldDefinition> fields = repository.findFields(SYSTEM_TENANT, account.id()).collectList().block();
        assertThat(fields).extracting(FieldDefinition::apiName)
                // findFields returns rows ordered by api_name
                .containsExactly("industry__c", "name", "phone__c", "website__c");

        assertField(fields, "name", FieldType.TEXT, StorageKind.COLUMN, true);
        assertField(fields, "industry__c", FieldType.PICKLIST, StorageKind.JSONB, false);
        assertField(fields, "website__c", FieldType.URL, StorageKind.JSONB, false);
        assertField(fields, "phone__c", FieldType.PHONE, StorageKind.JSONB, false);
    }

    @Test
    @DisplayName("Contact is loadable with all four seeded fields, including the LOOKUP to Account")
    void contactSeedIsQueryable() {
        ObjectDefinition contact = repository.findObject(SYSTEM_TENANT, "Contact").block();
        assertThat(contact).isNotNull();
        assertThat(contact.labelPlural()).isEqualTo("Contacts");

        List<FieldDefinition> fields = repository.findFields(SYSTEM_TENANT, contact.id()).collectList().block();
        assertThat(fields).extracting(FieldDefinition::apiName)
                .containsExactly("account_id__c", "email__c", "name", "phone__c");

        assertField(fields, "name", FieldType.TEXT, StorageKind.COLUMN, true);
        assertField(fields, "email__c", FieldType.EMAIL, StorageKind.JSONB, false);
        assertField(fields, "phone__c", FieldType.PHONE, StorageKind.JSONB, false);
        assertField(fields, "account_id__c", FieldType.LOOKUP, StorageKind.JSONB, false);
    }

    @Test
    @DisplayName("unknown object returns Mono.empty(), not an error")
    void unknownObjectIsEmpty() {
        StepVerifier.create(repository.findObject(SYSTEM_TENANT, "DoesNotExist"))
                .verifyComplete();
    }

    @Test
    @DisplayName("seed is idempotent: re-running the migrations does not duplicate or fail")
    void seedIsIdempotent() {
        // V2 uses ON CONFLICT DO NOTHING, so a repeat migrate must be a no-op, not an error.
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();

        ObjectDefinition account = repository.findObject(SYSTEM_TENANT, "Account").block();
        assertThat(account).isNotNull();
        Long fieldCount = repository.findFields(SYSTEM_TENANT, account.id()).count().block();
        assertThat(fieldCount).isEqualTo(4L); // still exactly four — no duplicates
    }

    private static void assertField(List<FieldDefinition> fields, String apiName,
                                    FieldType expectedType, StorageKind expectedStorage, boolean required) {
        FieldDefinition field = fields.stream()
                .filter(f -> f.apiName().equals(apiName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Field not found in seed: " + apiName));
        assertThat(field.fieldType()).as("type of %s", apiName).isEqualTo(expectedType);
        assertThat(field.storageKind()).as("storage of %s", apiName).isEqualTo(expectedStorage);
        assertThat(field.isRequired()).as("required flag of %s", apiName).isEqualTo(required);
    }
}
