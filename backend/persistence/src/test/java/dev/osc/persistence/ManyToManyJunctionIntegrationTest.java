package dev.osc.persistence;

import dev.osc.metadata.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.flywaydb.core.Flyway;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = ManyToManyJunctionIntegrationTest.TestConfig.class)
@DisplayName("MANY_TO_MANY Junction Object and Cascade Delete Integration")
class ManyToManyJunctionIntegrationTest {

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

        @Bean
        R2dbcRecordRepository r2dbcRecordRepository(DatabaseClient databaseClient) {
            return new R2dbcRecordRepository(databaseClient, new com.fasterxml.jackson.databind.ObjectMapper());
        }

        @Bean
        MetadataEngine metadataEngine(R2dbcMetadataRepository repository) {
            return new DelegatingMetadataEngine(repository);
        }

        @Bean
        FieldCoercionEngine fieldCoercionEngine() {
            return new DefaultFieldCoercionEngine();
        }

        @Bean
        DynamicPersistenceService dynamicPersistenceService(
                MetadataEngine metadataEngine,
                FieldCoercionEngine coercionEngine,
                RecordRepository recordRepository) {
            return new DefaultDynamicPersistenceService(metadataEngine, coercionEngine, recordRepository);
        }
    }

    @Autowired
    DatabaseClient client;

    @Autowired
    R2dbcMetadataRepository metadataRepository;

    @Autowired
    R2dbcRecordRepository recordRepository;

    @Autowired
    MetadataEngine metadataEngine;

    @Autowired
    DynamicPersistenceService persistenceService;

    @BeforeEach
    void migrateDb() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();
    }

    private static Function<Context, Context> tenant(UUID tenantId) {
        return ctx -> ctx.put(TenantContext.TENANT_ID_KEY, tenantId.toString());
    }

    private UUID insertTenant(String apiName) {
        return client.sql("INSERT INTO tenant (api_name, display_name) VALUES (:n, :d) RETURNING id")
                .bind("n", apiName)
                .bind("d", apiName + " Display")
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

    private UUID insertField(UUID tenantId, UUID objectId, String apiName, String fieldType) {
        return client.sql("""
                        INSERT INTO md_field (tenant_id, object_id, api_name, label, field_type, storage_kind, storage_key, is_required)
                        VALUES (:tid, :oid, :n, :n, :ft, 'JSONB', :n, FALSE) RETURNING id
                        """)
                .bind("tid", tenantId)
                .bind("oid", objectId)
                .bind("n", apiName)
                .bind("ft", fieldType)
                .map((row, meta) -> row.get("id", UUID.class))
                .one()
                .block();
    }

    @Test
    @DisplayName("Auto-creation of MANY_TO_MANY junction objects, fields, relationships, cascade deletes, and tenant isolation")
    void testManyToManyJunctionWorkflow() {
        // --- 1. SET UP TENANTS ---
        UUID tenantA = insertTenant("tenant_a");
        UUID tenantB = insertTenant("tenant_b");

        // --- 2. TENANT A: CREATE PARENT OBJECTS ---
        UUID empObjIdA = insertObject(tenantA, "Employee__c");
        UUID projObjIdA = insertObject(tenantA, "Project__c");

        insertField(tenantA, empObjIdA, "name", "TEXT");
        insertField(tenantA, projObjIdA, "name", "TEXT");

        // --- 3. TENANT A: INSERT MANY_TO_MANY RELATIONSHIP ---
        UUID m2mRelId = client.sql("""
                INSERT INTO md_relationship (tenant_id, relationship_type, child_object_id, parent_object_id, junction_object_api_name, on_delete)
                VALUES (:tid, 'MANY_TO_MANY', :childId, :parentId, 'Employee_Project__c', 'CASCADE')
                RETURNING id
                """)
                .bind("tid", tenantA)
                .bind("childId", empObjIdA)
                .bind("parentId", projObjIdA)
                .map((row, meta) -> row.get("id", UUID.class))
                .one()
                .block();

        assertThat(m2mRelId).isNotNull();

        // --- 4. VERIFY AUTOMATIC PROVISIONING FOR TENANT A ---
        // Verify junction object was created
        StepVerifier.create(metadataRepository.findObject(tenantA, "Employee_Project__c"))
                .assertNext(obj -> {
                    assertThat(obj.apiName()).isEqualTo("Employee_Project__c");
                    assertThat(obj.isCustom()).isTrue();
                    assertThat(obj.isJunction()).isTrue();
                })
                .verifyComplete();

        ObjectDefinition junctionObj = metadataRepository.findObject(tenantA, "Employee_Project__c").block();
        assertThat(junctionObj).isNotNull();

        // Verify two Master-Detail fields are created on the junction object
        StepVerifier.create(metadataRepository.findFields(tenantA, junctionObj.id()))
                .recordWith(java.util.ArrayList::new)
                .thenConsumeWhile(x -> true)
                .consumeRecordedWith(fields -> {
                    assertThat(fields).hasSize(2);
                    assertThat(fields).extracting(FieldDefinition::apiName)
                            .containsExactlyInAnyOrder("employee_id__c", "project_id__c");
                    assertThat(fields).extracting(FieldDefinition::fieldType)
                            .containsOnly(FieldType.MASTER_DETAIL);
                })
                .verifyComplete();

        // Verify two Master-Detail relationship definitions point from junction to the two parents
        StepVerifier.create(metadataRepository.findRelationships(tenantA, junctionObj.id()))
                .recordWith(java.util.ArrayList::new)
                .thenConsumeWhile(x -> true)
                .consumeRecordedWith(relationships -> {
                    // It should contain 3 relationships: 1 MANY_TO_MANY, and 2 MASTER_DETAIL relationships
                    assertThat(relationships).hasSize(3);

                    long m2mCount = relationships.stream().filter(r -> "MANY_TO_MANY".equals(r.relationshipType())).count();
                    long mdCount = relationships.stream().filter(r -> "MASTER_DETAIL".equals(r.relationshipType())).count();

                    assertThat(m2mCount).isEqualTo(1);
                    assertThat(mdCount).isEqualTo(2);

                    List<RelationshipDefinition> mdRels = relationships.stream()
                            .filter(r -> "MASTER_DETAIL".equals(r.relationshipType()))
                            .toList();

                    assertThat(mdRels).extracting(RelationshipDefinition::childObjectId)
                            .containsOnly(junctionObj.id());
                    assertThat(mdRels).extracting(RelationshipDefinition::parentObjectId)
                            .containsExactlyInAnyOrder(empObjIdA, projObjIdA);
                    assertThat(mdRels).extracting(RelationshipDefinition::onDelete)
                            .containsOnly("CASCADE");
                })
                .verifyComplete();

        // --- 5. TENANT B: VERIFY TENANT ISOLATION (SCHEMAS DO NOT LEAK) ---
        StepVerifier.create(metadataRepository.findObject(tenantB, "Employee_Project__c"))
                .verifyComplete(); // Empty, Tenant B cannot see Tenant A's junction object

        // --- 6. RECORD cascading DELETE TEST FOR TENANT A ---
        // Create an Employee and a Project record
        RecordEntity empRecord = persistenceService.createRecord("Employee__c", Map.of("name", "John Doe"))
                .contextWrite(tenant(tenantA))
                .block();
        assertThat(empRecord).isNotNull();

        RecordEntity projRecord = persistenceService.createRecord("Project__c", Map.of("name", "Secret Antigravity Engine"))
                .contextWrite(tenant(tenantA))
                .block();
        assertThat(projRecord).isNotNull();

        // Create a junction record linking them
        RecordEntity junctionRecord = persistenceService.createRecord("Employee_Project__c", Map.of(
                "employee_id__c", empRecord.id().toString(),
                "project_id__c", projRecord.id().toString()
        ))
                .contextWrite(tenant(tenantA))
                .block();
        assertThat(junctionRecord).isNotNull();

        // Verify we can retrieve it
        StepVerifier.create(persistenceService.getRecord(junctionRecord.id()).contextWrite(tenant(tenantA)))
                .assertNext(rec -> {
                    assertThat(rec.data().get("employee_id__c")).isEqualTo(empRecord.id().toString());
                    assertThat(rec.data().get("project_id__c")).isEqualTo(projRecord.id().toString());
                })
                .verifyComplete();

        // Tenant B: verify record isolation (cannot retrieve Tenant A's record)
        StepVerifier.create(persistenceService.getRecord(junctionRecord.id()).contextWrite(tenant(tenantB)))
                .verifyComplete(); // Empty, Tenant B cannot retrieve Tenant A's record

        // Delete the Employee record
        persistenceService.deleteRecord(empRecord.id())
                .contextWrite(tenant(tenantA))
                .block();

        // Verify the Employee record is gone
        StepVerifier.create(persistenceService.getRecord(empRecord.id()).contextWrite(tenant(tenantA)))
                .verifyComplete();

        // Verify the junction record was automatically cascade-deleted!
        StepVerifier.create(persistenceService.getRecord(junctionRecord.id()).contextWrite(tenant(tenantA)))
                .verifyComplete();

        // Verify the Project record remains untouched
        StepVerifier.create(persistenceService.getRecord(projRecord.id()).contextWrite(tenant(tenantA)))
                .assertNext(rec -> assertThat(rec.data().get("name")).isEqualTo("Secret Antigravity Engine"))
                .verifyComplete();
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
            return Mono.empty();
        }
    }
}
