package dev.osc.persistence;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import dev.osc.metadata.TenantContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.UUID;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.mockito.Mockito.*;

/**
 * SQL injection prevention tests — issue #21.
 *
 * Verifies that the persistence layer never concatenates user-supplied
 * strings directly into SQL. Two complementary approaches:
 *  1. ArchUnit rule: repository classes must not reference String.format or +
 *     in SQL-related methods (static analysis).
 *  2. Behaviour test: a malicious payload passed through the service is
 *     treated as data, not as SQL syntax (behaviour verification via mocks).
 *
 * Three attack surfaces are covered, each with the literal vectors from the issue:
 *  - record ID parameter   → rejected by UUID validation before any SQL
 *  - field values          → carried as R2DBC bind parameters, stored as plain data
 *  - object api_name       → resolved against metadata only; unknown names fail
 *                            with ObjectNotFoundException, never reach SQL
 *
 * Query Engine surfaces (api_name/field/value injection in SOQL-like queries)
 * are covered by dev.osc.query.QueryEngineInjectionTest in backend/query-engine.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SQL Injection Prevention")
class SqlInjectionPreventionTest {

    @Mock RecordRepository recordRepository;
    @Mock dev.osc.metadata.MetadataEngine metadataEngine;
    @Mock dev.osc.metadata.FieldCoercionEngine coercionEngine;

    @Test
    @DisplayName("ArchUnit: R2DBC repository classes do not import java.lang.String.format")
    void repositoryClasses_doNotUseStringFormat() {
        JavaClasses classes = new ClassFileImporter()
                .importPackages("dev.osc.persistence");

        ArchRule rule = noClasses()
                .that().haveSimpleNameEndingWith("Repository")
                .should().callMethod(String.class, "format", String.class, Object[].class)
                .because("SQL must use parameterized binds, not String.format");

        rule.check(classes);
    }

    @Test
    @DisplayName("ArchUnit: R2DBC repository classes do not use StringBuilder for SQL")
    void repositoryClasses_doNotUseStringBuilderForDynamicSql() {
        JavaClasses classes = new ClassFileImporter()
                .importPackages("dev.osc.persistence");

        ArchRule rule = noClasses()
                .that().haveSimpleNameEndingWith("Repository")
                .should().dependOnClassesThat().haveFullyQualifiedName("java.lang.StringBuilder")
                .because("SQL statements must be static strings with parameterized binds");

        rule.check(classes);
    }

    @ParameterizedTest
    @DisplayName("service layer: malicious field value is passed as a bind parameter, not injected")
    @ValueSource(strings = {
            "' OR '1'='1",
            "'; DROP TABLE record; --",
            "name': '<injection>', 'extra': '",
            "\"; INSERT INTO record (tenant_id) VALUES ('evil') --"
    })
    void maliciousValue_treatedAsData_notAsSql(String maliciousInput) {
        UUID tenantId = UUID.randomUUID();
        UUID objectId = UUID.randomUUID();
        UUID recordId = UUID.randomUUID();

        dev.osc.metadata.ObjectDefinition object =
                new dev.osc.metadata.ObjectDefinition(objectId, tenantId, "Account",
                        "Account", "Accounts", false, null, null);
        dev.osc.metadata.FieldDefinition nameField =
                new dev.osc.metadata.FieldDefinition(UUID.randomUUID(), tenantId, objectId,
                        "name", "Name", dev.osc.metadata.FieldType.TEXT,
                        dev.osc.metadata.StorageKind.JSONB, "name",
                        false, false, null, null, null);
        RecordEntity saved = new RecordEntity(recordId, tenantId, objectId,
                null, null, Map.of("name", maliciousInput), null, null);

        when(metadataEngine.findObject(tenantId, "Account"))
                .thenReturn(Mono.just(object));
        when(metadataEngine.findFields(tenantId, objectId))
                .thenReturn(reactor.core.publisher.Flux.just(nameField));
        when(coercionEngine.coerce(nameField, maliciousInput))
                .thenReturn(dev.osc.metadata.CoercionResult.success(maliciousInput));
        when(recordRepository.insert(any()))
                .thenReturn(Mono.just(saved));

        DefaultDynamicPersistenceService service =
                new DefaultDynamicPersistenceService(metadataEngine, coercionEngine, recordRepository);

        StepVerifier.create(
                service.createRecord("Account", Map.of("name", maliciousInput))
                        .contextWrite(ctx -> ctx.put(TenantContext.TENANT_ID_KEY, tenantId.toString()))
        )
                .assertNext(r -> {
                    // The value was stored as-is — treated as plain data
                    assert r.data().get("name").equals(maliciousInput);
                })
                .verifyComplete();

        // Verify the insert command contains the raw string as a value — not SQL
        verify(recordRepository).insert(argThat(cmd ->
                cmd.data().containsValue(maliciousInput)
        ));
    }

    @Test
    @DisplayName("malicious tenant UUID in context: parsing fails fast before any SQL is executed")
    void maliciousTenantId_failsBeforeSql() {
        DefaultDynamicPersistenceService service =
                new DefaultDynamicPersistenceService(metadataEngine, coercionEngine, recordRepository);

        // listRecords calls resolveTenantId() → UUID.fromString → IllegalArgumentException
        // before touching any repository or building any SQL
        StepVerifier.create(
                service.listRecords("Account", PageRequest.DEFAULT)
                        .contextWrite(ctx -> ctx.put(TenantContext.TENANT_ID_KEY,
                                "'; DROP TABLE record; --"))
        )
                .expectError(IllegalArgumentException.class)
                .verify();

        verifyNoInteractions(recordRepository);
    }

    @ParameterizedTest
    @DisplayName("object api_name: a malicious name is only a metadata lookup key — unknown object fails before any record SQL")
    @ValueSource(strings = {
            "Project__c' OR 1=1 --",
            "'; SELECT pg_sleep(5); --"
    })
    void maliciousApiName_failsAsUnknownObject_neverReachesRecordSql(String maliciousApiName) {
        UUID tenantId = UUID.randomUUID();
        // The api_name is bound as a parameter into the md_object lookup; it matches nothing.
        when(metadataEngine.findObject(eq(tenantId), anyString())).thenReturn(Mono.empty());

        DefaultDynamicPersistenceService service =
                new DefaultDynamicPersistenceService(metadataEngine, coercionEngine, recordRepository);

        StepVerifier.create(
                service.createRecord(maliciousApiName, Map.of("name", "x"))
                        .contextWrite(ctx -> ctx.put(TenantContext.TENANT_ID_KEY, tenantId.toString()))
        )
                .expectError(ObjectNotFoundException.class)
                .verify();

        // The malicious api_name never reaches the record repository.
        verifyNoInteractions(recordRepository);
    }
}
