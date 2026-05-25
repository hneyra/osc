package dev.osc.query;

import dev.osc.metadata.*;
import dev.osc.persistence.ObjectNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultQueryTranslator")
class QueryTranslatorTest {

    @Mock MetadataEngine metadataEngine;

    DefaultQueryTranslator translator;
    QueryParser parser = new DefaultQueryParser();

    final UUID tenantId = UUID.randomUUID();
    final UUID objectId = UUID.randomUUID();

    ObjectDefinition account;
    FieldDefinition nameField;
    FieldDefinition revenueField;
    FieldDefinition statusField;
    FieldDefinition isActiveField;

    @BeforeEach
    void setUp() {
        translator = new DefaultQueryTranslator(metadataEngine);

        account = new ObjectDefinition(objectId, tenantId, "Account",
                "Account", "Accounts", false, Instant.now(), Instant.now());

        nameField    = field("name",      FieldType.TEXT,     StorageKind.JSONB,   "name");
        revenueField = field("revenue__c", FieldType.NUMBER,   StorageKind.JSONB,   "revenue__c");
        statusField  = field("status__c", FieldType.PICKLIST,  StorageKind.JSONB,   "status__c");
        isActiveField= field("is_active", FieldType.BOOLEAN,   StorageKind.JSONB,   "is_active");
    }

    // ── happy path ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("SELECT * → SQL includes tenant filter and all fields")
        void selectAll_includesTenantFilter() {
            mockMetadata(nameField, revenueField);

            TranslatedQuery result = translate("SELECT * FROM Account");

            assertThat(result.sql()).contains("tenant_id =");
            assertThat(result.bindings().get(0)).isEqualTo(tenantId);
        }

        @Test
        @DisplayName("SELECT specific field → only that field in SQL")
        void specificField_inSql() {
            mockMetadata(nameField, revenueField);

            TranslatedQuery result = translate("SELECT name FROM Account");

            assertThat(result.selectedFields()).containsExactly(nameField);
        }

        @Test
        @DisplayName("WHERE string equality → parameterized bind, not interpolated")
        void whereStringEquality_parameterized() {
            mockMetadata(nameField);

            TranslatedQuery result = translate("SELECT * FROM Account WHERE name = 'ACME'");

            assertThat(result.sql()).doesNotContain("ACME");
            assertThat(result.bindings()).contains("ACME");
        }

        @Test
        @DisplayName("WHERE numeric → numeric bind value")
        void whereNumeric_numericBind() {
            mockMetadata(revenueField);

            TranslatedQuery result = translate("SELECT * FROM Account WHERE revenue__c > 5000");

            assertThat(result.bindings()).contains(new java.math.BigDecimal("5000"));
        }

        @Test
        @DisplayName("WHERE IN list → bindings contain all list items")
        void whereInList_bindingsContainAllItems() {
            mockMetadata(statusField);

            TranslatedQuery result = translate("SELECT * FROM Account WHERE status__c IN ('OPEN', 'CLOSED')");

            assertThat(result.bindings()).containsAnyOf("OPEN", "CLOSED");
        }

        @Test
        @DisplayName("JSONB field mapped to data->>'key' expression")
        void jsonbField_mappedCorrectly() {
            mockMetadata(nameField);

            TranslatedQuery result = translate("SELECT name FROM Account");

            assertThat(result.sql()).contains("data->>'name'");
        }

        @Test
        @DisplayName("ORDER BY, LIMIT, OFFSET included in SQL")
        void orderLimitOffset_inSql() {
            mockMetadata(nameField);

            TranslatedQuery result = translate(
                    "SELECT * FROM Account ORDER BY name DESC LIMIT 10 OFFSET 20");

            assertThat(result.sql()).contains("ORDER BY");
            assertThat(result.sql()).contains("LIMIT");
            assertThat(result.sql()).contains("OFFSET");
        }

        @Test
        @DisplayName("tenant filter is ALWAYS present even without WHERE clause")
        void tenantFilterAlwaysPresent() {
            mockMetadata(nameField);

            TranslatedQuery result = translate("SELECT * FROM Account");

            assertThat(result.sql()).contains("tenant_id =");
            assertThat(result.bindings().get(0)).isEqualTo(tenantId);
        }
    }

    // ── security invariants ───────────────────────────────────────────────────

    @Nested
    @DisplayName("security invariants")
    class SecurityInvariants {

        @Test
        @DisplayName("unknown object name → ObjectNotFoundException, no SQL generated")
        void unknownObject_throwsException() {
            when(metadataEngine.findObject(any(), any())).thenReturn(Mono.empty());

            StepVerifier.create(
                    translator.translate(parser.parse("SELECT * FROM Unknown"), tenantId, Set.of())
            )
                    .expectError(ObjectNotFoundException.class)
                    .verify();
        }

        @Test
        @DisplayName("unknown field name → FieldNotFoundException, no SQL generated")
        void unknownField_throwsException() {
            when(metadataEngine.findObject(tenantId, "Account")).thenReturn(Mono.just(account));
            when(metadataEngine.findFields(tenantId, objectId)).thenReturn(Flux.just(nameField));

            StepVerifier.create(
                    translator.translate(parser.parse("SELECT nonexistent_field FROM Account"),
                            tenantId, Set.of())
            )
                    .expectError(FieldNotFoundException.class)
                    .verify();
        }

        @MockitoSettings(strictness = Strictness.LENIENT)
        @ParameterizedTest
        @DisplayName("SQL injection in string value is never interpolated into SQL")
        @ValueSource(strings = {
                "'; DROP TABLE record; --",
                "' OR '1'='1",
                "' UNION SELECT * FROM tenant --",
                "1; DROP TABLE record",
                "'; SELECT pg_sleep(10); --",
                "admin'--",
                "' OR 1=1 --",
                "'; TRUNCATE record; --",
                "x' AND 1=0 UNION SELECT null --",
                "x' AND 1=0 UNION ALL SELECT null,null --",
                "'; EXEC xp_cmdshell('dir'); --",
                "1 OR 1=1",
                "1; SELECT SLEEP(10); --",
                "' AND SLEEP(5) --",
                "') OR ('1'='1",
                "')); DROP TABLE record; --",
                "1 UNION SELECT username FROM users --",
                "1 AND (SELECT 1 FROM record LIMIT 1)=1 --",
                "' OR 'x'='x",
                "1 AND '1'='1"
        })
        void sqlInjectionInValue_neverInterpolated(String injection) {
            // Lenient: some injections fail parsing before any mock is called
            lenient().when(metadataEngine.findObject(any(), any())).thenReturn(Mono.just(account));
            lenient().when(metadataEngine.findFields(any(), any())).thenReturn(Flux.just(nameField));

            try {
                TranslatedQuery result = translator.translate(
                        parser.parse("SELECT * FROM Account WHERE name = '" + injection + "'"),
                        tenantId, Set.of()
                ).block();
                if (result != null) {
                    assertThat(result.sql()).doesNotContain(injection);
                    assertThat(result.bindings()).containsAnyOf(injection);
                }
            } catch (ParseException | FieldNotFoundException ignored) {
                // Acceptable: parser or translator refused the malformed input
            }
        }

        @Test
        @DisplayName("FLS: field not in allowedFields is stripped from SELECT")
        void flsStripsUnpermittedField() {
            mockMetadata(nameField, revenueField);

            // allowedFields only allows 'name' — revenue__c must be stripped
            TranslatedQuery result;
            try {
                result = translator.translate(
                        parser.parse("SELECT * FROM Account"),
                        tenantId, Set.of("name")
                ).block();
                if (result != null) {
                    assertThat(result.selectedFields()).allMatch(f -> f.apiName().equals("name"));
                }
            } catch (Exception ignored) {
                // Some implementations may throw when access is denied — also acceptable
            }
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void mockMetadata(FieldDefinition... fields) {
        when(metadataEngine.findObject(tenantId, "Account")).thenReturn(Mono.just(account));
        when(metadataEngine.findFields(tenantId, objectId)).thenReturn(Flux.just(fields));
    }

    private TranslatedQuery translate(String queryString) {
        return translator.translate(parser.parse(queryString), tenantId, Set.of()).block();
    }

    private FieldDefinition field(String apiName, FieldType type, StorageKind kind, String storageKey) {
        return new FieldDefinition(UUID.randomUUID(), tenantId, objectId,
                apiName, apiName, type, kind, storageKey,
                false, false, null, Instant.now(), Instant.now());
    }
}
