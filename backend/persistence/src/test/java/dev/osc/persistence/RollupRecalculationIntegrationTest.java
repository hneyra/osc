package dev.osc.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.osc.automation.outbox.*;
import dev.osc.metadata.*;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Rollup Recalculation Integration Tests")
class RollupRecalculationIntegrationTest {

    @SuppressWarnings("resource")
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("osc_rollup_test")
                    .withUsername("osc")
                    .withPassword("test");

    private static DatabaseClient client;
    private static ObjectMapper mapper;
    private static R2dbcMetadataRepository metadataRepository;
    private static DelegatingMetadataEngine metadataEngine;
    private static FieldCoercionEngine coercionEngine;
    private static R2dbcRecordRepository recordRepository;
    private static R2dbcOutboxRepository outboxRepository;
    private static DefaultDynamicPersistenceService service;
    private static RollupEventPublisher rollupEventPublisher;
    private static OutboxWorker worker;

    private UUID tenantA;
    private UUID accountObjectId;
    private UUID opportunityObjectId;
    private UUID relationshipId;

    // Relationship Lookup Field ID
    private UUID lookupFieldId;

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

        mapper = new ObjectMapper();
        metadataRepository = new R2dbcMetadataRepository(client);
        metadataEngine = new DelegatingMetadataEngine(metadataRepository);
        coercionEngine = new DefaultFieldCoercionEngine();
        recordRepository = new R2dbcRecordRepository(client, mapper);
        outboxRepository = new R2dbcOutboxRepository(connectionFactory, mapper);

        service = new DefaultDynamicPersistenceService(metadataEngine, coercionEngine, recordRepository, outboxRepository);

        rollupEventPublisher = new RollupEventPublisher(
                new LoggingEventPublisher(),
                metadataEngine,
                recordRepository,
                client,
                mapper
        );

        worker = new OutboxWorker(outboxRepository, rollupEventPublisher);
    }

    @BeforeEach
    void seedSchema() {
        tenantA = insertTenant();
        accountObjectId = insertObject(tenantA, "Account");
        opportunityObjectId = insertObject(tenantA, "Opportunity");

        // Fields on Account (Parent)
        insertField(tenantA, accountObjectId, "name", "TEXT", "name", false, null);

        // Fields on Opportunity (Child)
        insertField(tenantA, opportunityObjectId, "name", "TEXT", "name", false, null);
        insertField(tenantA, opportunityObjectId, "amount__c", "NUMBER", "amount__c", false, null);
        insertField(tenantA, opportunityObjectId, "stage__c", "TEXT", "stage__c", false, null);

        // Child lookup field referencing Account
        lookupFieldId = insertField(tenantA, opportunityObjectId, "account__c", "MASTER_DETAIL", "account__c", false, null);

        // Master-Detail Relationship
        relationshipId = insertRelationship(tenantA, "MASTER_DETAIL", opportunityObjectId, accountObjectId, lookupFieldId, "CASCADE");

        // Now that relationshipId exists, we can add the ROLLUP fields on Account (Parent)
        insertField(tenantA, accountObjectId, "total_value__c", "ROLLUP", "total_value__c", false,
                """
                {
                  "rollup": {
                    "relationshipId": "%s",
                    "aggregate": "SUM",
                    "sourceFieldApiName": "amount__c"
                  }
                }
                """.formatted(relationshipId));

        insertField(tenantA, accountObjectId, "active_opps_count__c", "ROLLUP", "active_opps_count__c", false,
                """
                {
                  "rollup": {
                    "relationshipId": "%s",
                    "aggregate": "COUNT",
                    "filterExpression": "stage__c == 'Closed Won'"
                  }
                }
                """.formatted(relationshipId));

        insertField(tenantA, accountObjectId, "min_amount__c", "ROLLUP", "min_amount__c", false,
                """
                {
                  "rollup": {
                    "relationshipId": "%s",
                    "aggregate": "MIN",
                    "sourceFieldApiName": "amount__c"
                  }
                }
                """.formatted(relationshipId));

        insertField(tenantA, accountObjectId, "max_amount__c", "ROLLUP", "max_amount__c", false,
                """
                {
                  "rollup": {
                    "relationshipId": "%s",
                    "aggregate": "MAX",
                    "sourceFieldApiName": "amount__c"
                  }
                }
                """.formatted(relationshipId));

        insertField(tenantA, accountObjectId, "avg_amount__c", "ROLLUP", "avg_amount__c", false,
                """
                {
                  "rollup": {
                    "relationshipId": "%s",
                    "aggregate": "AVG",
                    "sourceFieldApiName": "amount__c"
                  }
                }
                """.formatted(relationshipId));
    }

    @Test
    @DisplayName("NNG-027: Rollup enqueued immediately but non-blocking (eventual consistency)")
    void testEventualConsistencyAndNonBlocking() {
        // Create Parent
        RecordEntity parent = service.createRecord("Account", Map.of("name", "Parent Company"))
                .contextWrite(tenant(tenantA))
                .block();
        assertThat(parent).isNotNull();

        // Create Child
        RecordEntity child = service.createRecord("Opportunity", Map.of(
                "name", "Huge Sale",
                "amount__c", 1000.0,
                "stage__c", "Prospecting",
                "account__c", parent.id().toString()
        )).contextWrite(tenant(tenantA)).block();
        assertThat(child).isNotNull();

        // Fetch parent right after inserting child — aggregate values should STILL be null/unchanged
        RecordEntity parentInstantFetch = service.getRecord(parent.id())
                .contextWrite(tenant(tenantA))
                .block();
        assertThat(parentInstantFetch).isNotNull();
        assertThat(parentInstantFetch.data().get("total_value__c")).isNull();

        // Verify there is a PENDING outbox event
        List<OutboxEvent> pending = outboxRepository.findPending(10).collectList().block();
        assertThat(pending).isNotEmpty();
        assertThat(pending.get(0).eventType()).isEqualTo("ROLLUP_RECOMPUTE");
        assertThat(pending.get(0).status()).isEqualTo(OutboxEventStatus.PENDING);
    }

    @Test
    @DisplayName("Rollup computation: verifies SUM, COUNT with filter, MIN, MAX, AVG")
    void testAggregateTypesAndFiltering() {
        // Create Parent
        RecordEntity parent = service.createRecord("Account", Map.of("name", "Test Parent"))
                .contextWrite(tenant(tenantA))
                .block();

        // Create children
        service.createRecord("Opportunity", Map.of(
                "name", "Opp 1",
                "amount__c", 100.0,
                "stage__c", "Prospecting",
                "account__c", parent.id().toString()
        )).contextWrite(tenant(tenantA)).block();

        service.createRecord("Opportunity", Map.of(
                "name", "Opp 2",
                "amount__c", 250.0,
                "stage__c", "Closed Won",
                "account__c", parent.id().toString()
        )).contextWrite(tenant(tenantA)).block();

        service.createRecord("Opportunity", Map.of(
                "name", "Opp 3",
                "amount__c", 150.0,
                "stage__c", "Closed Won",
                "account__c", parent.id().toString()
        )).contextWrite(tenant(tenantA)).block();

        // Process all pending outbox events
        worker.processPending().block();

        // Fetch parent and assert rollup values
        RecordEntity updatedParent = service.getRecord(parent.id())
                .contextWrite(tenant(tenantA))
                .block();
        assertThat(updatedParent).isNotNull();

        // SUM = 100 + 250 + 150 = 500
        assertThat(getAsDouble(updatedParent.data().get("total_value__c"))).isEqualTo(500.0);

        // COUNT filtered by stage__c == 'Closed Won' = 2
        assertThat(getAsDouble(updatedParent.data().get("active_opps_count__c"))).isEqualTo(2.0);

        // MIN = 100
        assertThat(getAsDouble(updatedParent.data().get("min_amount__c"))).isEqualTo(100.0);

        // MAX = 250
        assertThat(getAsDouble(updatedParent.data().get("max_amount__c"))).isEqualTo(250.0);

        // AVG = (100 + 250 + 150) / 3 = 166.66666666666666
        assertThat(getAsDouble(updatedParent.data().get("avg_amount__c"))).isCloseTo(166.67, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    @DisplayName("Tenant Isolation: Tenant B children are not rolled up into Tenant A's parent")
    void testTenantIsolation() {
        // Create Tenant B
        UUID tenantB = insertTenant();
        UUID accountObjectIdB = insertObject(tenantB, "Account");
        UUID opportunityObjectIdB = insertObject(tenantB, "Opportunity");

        insertField(tenantB, accountObjectIdB, "name", "TEXT", "name", false, null);
        UUID lookupFieldIdB = insertField(tenantB, opportunityObjectIdB, "account__c", "MASTER_DETAIL", "account__c", false, null);
        UUID relationshipIdB = insertRelationship(tenantB, "MASTER_DETAIL", opportunityObjectIdB, accountObjectIdB, lookupFieldIdB, "CASCADE");

        insertField(tenantB, accountObjectIdB, "total_value__c", "ROLLUP", "total_value__c", false,
                """
                {
                  "rollup": {
                    "relationshipId": "%s",
                    "aggregate": "SUM",
                    "sourceFieldApiName": "amount__c"
                  }
                }
                """.formatted(relationshipIdB));
        insertField(tenantB, opportunityObjectIdB, "name", "TEXT", "name", false, null);
        insertField(tenantB, opportunityObjectIdB, "amount__c", "NUMBER", "amount__c", false, null);

        // Parent A (Tenant A)
        RecordEntity parentA = service.createRecord("Account", Map.of("name", "Parent A"))
                .contextWrite(tenant(tenantA))
                .block();

        // Parent B (Tenant B)
        RecordEntity parentB = service.createRecord("Account", Map.of("name", "Parent B"))
                .contextWrite(tenant(tenantB))
                .block();

        // Child A (Tenant A) referencing Parent A, amount = 100
        service.createRecord("Opportunity", Map.of(
                "name", "Child A",
                "amount__c", 100.0,
                "account__c", parentA.id().toString()
        )).contextWrite(tenant(tenantA)).block();

        // Child B (Tenant B) referencing Parent B, amount = 200
        service.createRecord("Opportunity", Map.of(
                "name", "Child B",
                "amount__c", 200.0,
                "account__c", parentB.id().toString()
        )).contextWrite(tenant(tenantB)).block();

        // Process all pending outbox events (A and B)
        worker.processPending().block();

        // Verify Parent A has aggregate = 100
        RecordEntity updatedParentA = service.getRecord(parentA.id())
                .contextWrite(tenant(tenantA))
                .block();
        assertThat(getAsDouble(updatedParentA.data().get("total_value__c"))).isEqualTo(100.0);

        // Verify Parent B has aggregate = 200
        RecordEntity updatedParentB = service.getRecord(parentB.id())
                .contextWrite(tenant(tenantB))
                .block();
        assertThat(getAsDouble(updatedParentB.data().get("total_value__c"))).isEqualTo(200.0);
    }

    @Test
    @DisplayName("Idempotency: reprocessing the same rollup outbox event is safe and idempotent")
    void testIdempotency() {
        RecordEntity parent = service.createRecord("Account", Map.of("name", "Idempotent Account"))
                .contextWrite(tenant(tenantA))
                .block();

        service.createRecord("Opportunity", Map.of(
                "name", "Sale",
                "amount__c", 350.0,
                "account__c", parent.id().toString()
        )).contextWrite(tenant(tenantA)).block();

        // Get the enqueued event
        OutboxEvent event = outboxRepository.findPending(10)
                .filter(ev -> ev.aggregateId().equals(parent.id()))
                .next()
                .block();
        assertThat(event).isNotNull();

        // Manually publish event once
        rollupEventPublisher.publish(event).block();

        RecordEntity parentFetchedOnce = service.getRecord(parent.id())
                .contextWrite(tenant(tenantA))
                .block();
        assertThat(getAsDouble(parentFetchedOnce.data().get("total_value__c"))).isEqualTo(350.0);

        // Manually publish event a second time
        rollupEventPublisher.publish(event).block();

        RecordEntity parentFetchedTwice = service.getRecord(parent.id())
                .contextWrite(tenant(tenantA))
                .block();
        // Value should STILL be 350.0, not doubled to 700.0
        assertThat(getAsDouble(parentFetchedTwice.data().get("total_value__c"))).isEqualTo(350.0);
    }

    @Test
    @DisplayName("Non-blocking: main child record transaction is unaffected if outbox enqueuing fails")
    void testNonBlockingOnEnqueuingFailure() {
        // Construct a service with a failing OutboxRepository
        OutboxRepository failingOutboxRepository = new OutboxRepository() {
            @Override
            public Mono<OutboxEvent> save(OutboxEvent event) {
                return Mono.error(new RuntimeException("Database connection failure"));
            }

            @Override
            public Flux<OutboxEvent> findPending(int limit) {
                return Flux.empty();
            }

            @Override
            public Mono<Void> markProcessed(UUID id) {
                return Mono.empty();
            }

            @Override
            public Mono<Void> markFailed(UUID id) {
                return Mono.empty();
            }

            @Override
            public Mono<Void> incrementAttempts(UUID id) {
                return Mono.empty();
            }
        };

        DefaultDynamicPersistenceService failingService = new DefaultDynamicPersistenceService(
                metadataEngine, coercionEngine, recordRepository, failingOutboxRepository
        );

        RecordEntity parent = service.createRecord("Account", Map.of("name", "Parent"))
                .contextWrite(tenant(tenantA))
                .block();

        // Creating child should succeed even if the outbox fails
        RecordEntity child = failingService.createRecord("Opportunity", Map.of(
                "name", "Failing Outbox Opp",
                "amount__c", 100.0,
                "account__c", parent.id().toString()
        )).contextWrite(tenant(tenantA)).block();

        assertThat(child).isNotNull();
        assertThat(child.data().get("name")).isEqualTo("Failing Outbox Opp");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Function<Context, Context> tenant(UUID tenantId) {
        return ctx -> ctx.put(TenantContext.TENANT_ID_KEY, tenantId.toString());
    }

    private UUID insertTenant() {
        return client.sql("INSERT INTO tenant (api_name, display_name) VALUES (:n, :d) RETURNING id")
                .bind("n", "t-" + UUID.randomUUID())
                .bind("d", "Rollup IT Tenant")
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

    private UUID insertField(UUID tenantId, UUID objectId, String apiName,
                             String fieldType, String storageKey, boolean required, String config) {
        return client.sql("""
                        INSERT INTO md_field
                            (tenant_id, object_id, api_name, label, field_type, storage_kind, storage_key, is_required, is_custom, config)
                        VALUES (:tid, :oid, :n, :n, :ft, 'JSONB', :sk, :req, TRUE, :config::jsonb)
                        RETURNING id
                        """)
                .bind("tid", tenantId)
                .bind("oid", objectId)
                .bind("n", apiName)
                .bind("ft", fieldType)
                .bind("sk", storageKey)
                .bind("req", required)
                .bind("config", config == null ? "{}" : config)
                .map((row, meta) -> row.get("id", UUID.class))
                .one()
                .block();
    }

    private UUID insertRelationship(UUID tenantId, String type, UUID childId, UUID parentId, UUID fieldId, String onDelete) {
        return client.sql("""
                        INSERT INTO md_relationship
                            (tenant_id, relationship_type, child_object_id, parent_object_id, field_id, on_delete)
                        VALUES (:tid, :type, :childId, :parentId, :fieldId, :onDelete)
                        RETURNING id
                        """)
                .bind("tid", tenantId)
                .bind("type", type)
                .bind("childId", childId)
                .bind("parentId", parentId)
                .bind("fieldId", fieldId)
                .bind("onDelete", onDelete)
                .map((row, meta) -> row.get("id", UUID.class))
                .one()
                .block();
    }

    private Double getAsDouble(Object val) {
        if (val == null) return null;
        if (val instanceof Number) return ((Number) val).doubleValue();
        try {
            return Double.parseDouble(val.toString());
        } catch (Exception e) {
            return null;
        }
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
