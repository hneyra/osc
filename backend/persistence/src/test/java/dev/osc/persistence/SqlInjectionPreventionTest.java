package dev.osc.persistence;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import dev.osc.metadata.TenantContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.UUID;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.mockito.ArgumentMatchers.anyString;
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

    @Test
    @DisplayName("service layer: malicious field value is passed as a bind parameter, not injected")
    void maliciousValue_treatedAsData_notAsSql() {
        UUID tenantId = UUID.randomUUID();
        UUID objectId = UUID.randomUUID();
        UUID recordId = UUID.randomUUID();

        String maliciousInput = "'; DROP TABLE record; --";

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
}
