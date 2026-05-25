package dev.osc.metadata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DefaultFieldCoercionEngine")
class DefaultFieldCoercionEngineTest {

    private final DefaultFieldCoercionEngine engine = new DefaultFieldCoercionEngine();

    // ── helpers ──────────────────────────────────────────────────────────────

    private static FieldDefinition field(FieldType type, boolean required) {
        return field(type, required, null);
    }

    private static FieldDefinition field(FieldType type, boolean required, String config) {
        return new FieldDefinition(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "test__c", "Test", type, StorageKind.JSONB, "test__c",
                required, true, config, null, null);
    }

    // ── Null / Required ───────────────────────────────────────────────────────

    @Test @DisplayName("null on required field → Failure")
    void null_requiredField_returnsFailure() {
        var result = engine.coerce(field(FieldType.TEXT, true), null);
        assertInstanceOf(CoercionResult.Failure.class, result);
    }

    @Test @DisplayName("null on optional field → Success(null)")
    void null_optionalField_returnsNullSuccess() {
        var result = engine.coerce(field(FieldType.TEXT, false), null);
        assertInstanceOf(CoercionResult.Success.class, result);
        assertNull(((CoercionResult.Success) result).typedValue());
    }

    // ── TEXT ─────────────────────────────────────────────────────────────────

    @Nested @DisplayName("TEXT")
    class Text {
        @Test void string_returnsString() {
            var r = engine.coerce(field(FieldType.TEXT, false), "hello");
            assertEquals(CoercionResult.success("hello"), r);
        }

        @Test void nonString_convertedToString() {
            var r = engine.coerce(field(FieldType.TEXT, false), 42);
            assertEquals(CoercionResult.success("42"), r);
        }
    }

    // ── NUMBER ────────────────────────────────────────────────────────────────

    @Nested @DisplayName("NUMBER")
    class Number {
        @Test void integerString_returnsBigDecimal() {
            var r = engine.coerce(field(FieldType.NUMBER, false), "123");
            assertEquals(new BigDecimal("123"), ((CoercionResult.Success) r).typedValue());
        }

        @Test void decimalString_returnsBigDecimal() {
            var r = engine.coerce(field(FieldType.NUMBER, false), "3.14");
            assertTrue(r.isSuccess());
        }

        @Test void numericValue_returnsBigDecimal() {
            var r = engine.coerce(field(FieldType.NUMBER, false), 99.5);
            assertTrue(r.isSuccess());
        }

        @Test void nonNumericString_returnsFailure() {
            var r = engine.coerce(field(FieldType.NUMBER, false), "abc");
            assertTrue(r.isFailure());
        }
    }

    // ── DATE ──────────────────────────────────────────────────────────────────

    @Nested @DisplayName("DATE")
    class DateField {
        @Test void isoDate_returnsLocalDate() {
            var r = engine.coerce(field(FieldType.DATE, false), "2026-01-15");
            assertInstanceOf(LocalDate.class, ((CoercionResult.Success) r).typedValue());
        }

        @Test void invalidDate_returnsFailure() {
            assertTrue(engine.coerce(field(FieldType.DATE, false), "not-a-date").isFailure());
        }
    }

    // ── DATETIME ─────────────────────────────────────────────────────────────

    @Nested @DisplayName("DATETIME")
    class DateTimeField {
        @Test void isoDatetime_returnsOffsetDateTime() {
            var r = engine.coerce(field(FieldType.DATETIME, false), "2026-01-15T10:30:00Z");
            assertInstanceOf(OffsetDateTime.class, ((CoercionResult.Success) r).typedValue());
        }

        @Test void invalid_returnsFailure() {
            assertTrue(engine.coerce(field(FieldType.DATETIME, false), "bad").isFailure());
        }
    }

    // ── TIME ──────────────────────────────────────────────────────────────────

    @Nested @DisplayName("TIME")
    class TimeField {
        @Test void validTime_returnsLocalTime() {
            var r = engine.coerce(field(FieldType.TIME, false), "14:30:00");
            assertInstanceOf(LocalTime.class, ((CoercionResult.Success) r).typedValue());
        }
    }

    // ── BOOLEAN ───────────────────────────────────────────────────────────────

    @Nested @DisplayName("BOOLEAN")
    class BooleanField {
        @ParameterizedTest @ValueSource(strings = {"true", "TRUE", "1"})
        void truthyValues_returnsTrue(String val) {
            var r = engine.coerce(field(FieldType.BOOLEAN, false), val);
            assertEquals(CoercionResult.success(Boolean.TRUE), r);
        }

        @ParameterizedTest @ValueSource(strings = {"false", "FALSE", "0"})
        void falsyValues_returnsFalse(String val) {
            var r = engine.coerce(field(FieldType.BOOLEAN, false), val);
            assertEquals(CoercionResult.success(Boolean.FALSE), r);
        }

        @Test void booleanInput_returnsBoolean() {
            assertEquals(CoercionResult.success(Boolean.TRUE),
                    engine.coerce(field(FieldType.BOOLEAN, false), Boolean.TRUE));
        }

        @Test void invalidString_returnsFailure() {
            assertTrue(engine.coerce(field(FieldType.BOOLEAN, false), "maybe").isFailure());
        }
    }

    // ── PICKLIST ─────────────────────────────────────────────────────────────

    @Nested @DisplayName("PICKLIST")
    class Picklist {
        private static final String CONFIG =
                "{\"picklistValues\":[{\"value\":\"OPEN\"},{\"value\":\"CLOSED\"}]}";

        @Test void validValue_returnsSuccess() {
            var r = engine.coerce(field(FieldType.PICKLIST, false, CONFIG), "OPEN");
            assertEquals(CoercionResult.success("OPEN"), r);
        }

        @Test void invalidValue_returnsFailure() {
            assertTrue(engine.coerce(field(FieldType.PICKLIST, false, CONFIG), "UNKNOWN").isFailure());
        }

        @Test void nullConfig_acceptsAnyValue() {
            var r = engine.coerce(field(FieldType.PICKLIST, false, null), "ANYTHING");
            assertTrue(r.isSuccess());
        }
    }

    // ── EMAIL ─────────────────────────────────────────────────────────────────

    @Nested @DisplayName("EMAIL")
    class EmailField {
        @Test void validEmail_returnsSuccess() {
            assertTrue(engine.coerce(field(FieldType.EMAIL, false), "user@example.com").isSuccess());
        }

        @Test void invalidEmail_returnsFailure() {
            assertTrue(engine.coerce(field(FieldType.EMAIL, false), "not-an-email").isFailure());
        }
    }

    // ── URL ───────────────────────────────────────────────────────────────────

    @Nested @DisplayName("URL")
    class UrlField {
        @Test void validHttpUrl_returnsSuccess() {
            assertTrue(engine.coerce(field(FieldType.URL, false), "https://example.com").isSuccess());
        }

        @Test void invalidUrl_returnsFailure() {
            assertTrue(engine.coerce(field(FieldType.URL, false), "not a url").isFailure());
        }
    }

    // ── LOOKUP ────────────────────────────────────────────────────────────────

    @Nested @DisplayName("LOOKUP")
    class LookupField {
        @Test void validUuid_returnsUUID() {
            String uuid = UUID.randomUUID().toString();
            var r = engine.coerce(field(FieldType.LOOKUP, false), uuid);
            assertInstanceOf(UUID.class, ((CoercionResult.Success) r).typedValue());
        }

        @Test void invalidUuid_returnsFailure() {
            assertTrue(engine.coerce(field(FieldType.LOOKUP, false), "not-uuid").isFailure());
        }
    }

    // ── Read-only types ───────────────────────────────────────────────────────

    @ParameterizedTest
    @DisplayName("AUTO_NUMBER and FORMULA always reject input")
    @org.junit.jupiter.params.provider.EnumSource(value = FieldType.class,
            names = {"AUTO_NUMBER", "FORMULA"})
    void readOnly_alwaysRejectsInput(FieldType type) {
        assertTrue(engine.coerce(field(type, false), "any").isFailure());
    }
}
