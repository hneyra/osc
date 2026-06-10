package dev.osc.metadata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Arrays;
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

    private static Object value(CoercionResult r) {
        assertInstanceOf(CoercionResult.Success.class, r,
                () -> "expected Success but was " + r);
        return ((CoercionResult.Success) r).typedValue();
    }

    // ── Null / Required (full matrix: every field type) ──────────────────────

    @ParameterizedTest
    @DisplayName("null on required field → Failure (all types)")
    @EnumSource(FieldType.class)
    void null_requiredField_returnsFailure(FieldType type) {
        var result = engine.coerce(field(type, true), null);
        assertInstanceOf(CoercionResult.Failure.class, result);
    }

    @ParameterizedTest
    @DisplayName("null on optional field → Success(null) (all types)")
    @EnumSource(FieldType.class)
    void null_optionalField_returnsNullSuccess(FieldType type) {
        var result = engine.coerce(field(type, false), null);
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

        @Test void withinMaxLength_returnsSuccess() {
            var r = engine.coerce(field(FieldType.TEXT, false, "{\"maxLength\":5}"), "abcde");
            assertEquals(CoercionResult.success("abcde"), r);
        }

        @Test void exceedsMaxLength_returnsFailure() {
            var r = engine.coerce(field(FieldType.TEXT, false, "{\"maxLength\":5}"), "abcdef");
            assertTrue(r.isFailure());
        }
    }

    // ── TEXTAREA ─────────────────────────────────────────────────────────────

    @Nested @DisplayName("TEXTAREA")
    class TextArea {
        @Test void multilineString_returnsString() {
            var r = engine.coerce(field(FieldType.TEXTAREA, false), "line1\nline2");
            assertEquals(CoercionResult.success("line1\nline2"), r);
        }

        @Test void nonString_convertedToString() {
            var r = engine.coerce(field(FieldType.TEXTAREA, false), 3.5);
            assertEquals(CoercionResult.success("3.5"), r);
        }

        @Test void exceedsMaxLength_returnsFailure() {
            var r = engine.coerce(field(FieldType.TEXTAREA, false, "{\"maxLength\":3}"), "abcd");
            assertTrue(r.isFailure());
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

        @Test void scaleFromConfig_isApplied() {
            var r = engine.coerce(field(FieldType.NUMBER, false, "{\"scale\":2}"), "3.14159");
            assertEquals(new BigDecimal("3.14"), value(r));
        }

        @Test void exceedsPrecision_returnsFailure() {
            var r = engine.coerce(field(FieldType.NUMBER, false, "{\"precision\":4}"), "123456");
            assertTrue(r.isFailure());
        }

        @Test void withinPrecision_returnsSuccess() {
            var r = engine.coerce(field(FieldType.NUMBER, false, "{\"precision\":6}"), "123456");
            assertTrue(r.isSuccess());
        }
    }

    // ── CURRENCY ─────────────────────────────────────────────────────────────

    @Nested @DisplayName("CURRENCY")
    class Currency {
        @Test void defaultsToTwoDecimals() {
            var r = engine.coerce(field(FieldType.CURRENCY, false), "19.999");
            assertEquals(new BigDecimal("20.00"), value(r));
        }

        @Test void integerInput_scaledToTwoDecimals() {
            var r = engine.coerce(field(FieldType.CURRENCY, false), 100);
            assertEquals(new BigDecimal("100.00"), value(r));
        }

        @Test void configScale_overridesDefault() {
            var r = engine.coerce(field(FieldType.CURRENCY, false, "{\"scale\":4}"), "1.23456");
            assertEquals(new BigDecimal("1.2346"), value(r));
        }

        @Test void nonNumeric_returnsFailure() {
            assertTrue(engine.coerce(field(FieldType.CURRENCY, false), "ten dollars").isFailure());
        }
    }

    // ── PERCENT ──────────────────────────────────────────────────────────────

    @Nested @DisplayName("PERCENT")
    class Percent {
        @Test void numericString_returnsBigDecimal() {
            var r = engine.coerce(field(FieldType.PERCENT, false), "12.5");
            assertEquals(new BigDecimal("12.5"), value(r));
        }

        @Test void nonNumeric_returnsFailure() {
            assertTrue(engine.coerce(field(FieldType.PERCENT, false), "half").isFailure());
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

        @Test void wrongFormat_returnsFailure() {
            assertTrue(engine.coerce(field(FieldType.DATE, false), "15/01/2026").isFailure());
        }
    }

    // ── DATETIME ─────────────────────────────────────────────────────────────

    @Nested @DisplayName("DATETIME")
    class DateTimeField {
        @Test void isoDatetime_returnsOffsetDateTime() {
            var r = engine.coerce(field(FieldType.DATETIME, false), "2026-01-15T10:30:00Z");
            assertInstanceOf(OffsetDateTime.class, ((CoercionResult.Success) r).typedValue());
        }

        @Test void offsetDatetime_returnsOffsetDateTime() {
            var r = engine.coerce(field(FieldType.DATETIME, false), "2026-01-15T10:30:00+02:00");
            assertInstanceOf(OffsetDateTime.class, value(r));
        }

        @Test void missingTimezone_returnsFailure() {
            assertTrue(engine.coerce(field(FieldType.DATETIME, false), "2026-01-15T10:30:00").isFailure());
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

        @Test void invalidTime_returnsFailure() {
            assertTrue(engine.coerce(field(FieldType.TIME, false), "25:99:99").isFailure());
        }

        @Test void nonTimeString_returnsFailure() {
            assertTrue(engine.coerce(field(FieldType.TIME, false), "noon").isFailure());
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

        @Test void numericInput_returnsBoolean() {
            assertEquals(CoercionResult.success(Boolean.TRUE),
                    engine.coerce(field(FieldType.BOOLEAN, false), 1));
            assertEquals(CoercionResult.success(Boolean.FALSE),
                    engine.coerce(field(FieldType.BOOLEAN, false), 0));
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

    // ── MULTIPICKLIST ────────────────────────────────────────────────────────

    @Nested @DisplayName("MULTIPICKLIST")
    class MultiPicklist {
        private static final String CONFIG =
                "{\"picklistValues\":[{\"value\":\"RED\"},{\"value\":\"GREEN\"},{\"value\":\"BLUE\"}]}";

        @Test void semicolonString_allInOptions_returnsSuccess() {
            var r = engine.coerce(field(FieldType.MULTIPICKLIST, false, CONFIG), "RED;BLUE");
            assertEquals("RED;BLUE", value(r));
        }

        @Test void semicolonString_withSpaces_isTrimmed() {
            var r = engine.coerce(field(FieldType.MULTIPICKLIST, false, CONFIG), "RED; GREEN");
            assertEquals("RED;GREEN", value(r));
        }

        @Test void collectionInput_allInOptions_returnsSuccess() {
            var r = engine.coerce(field(FieldType.MULTIPICKLIST, false, CONFIG),
                    List.of("GREEN", "BLUE"));
            assertEquals("GREEN;BLUE", value(r));
        }

        @Test void oneValueNotInOptions_returnsFailure() {
            assertTrue(engine.coerce(field(FieldType.MULTIPICKLIST, false, CONFIG),
                    "RED;PURPLE").isFailure());
        }

        @Test void collectionWithInvalidValue_returnsFailure() {
            assertTrue(engine.coerce(field(FieldType.MULTIPICKLIST, false, CONFIG),
                    List.of("RED", "PURPLE")).isFailure());
        }

        @Test void collectionWithNullEntry_returnsFailure() {
            assertTrue(engine.coerce(field(FieldType.MULTIPICKLIST, false, CONFIG),
                    Arrays.asList("RED", null)).isFailure());
        }

        @Test void nullConfig_acceptsAnyValues() {
            assertTrue(engine.coerce(field(FieldType.MULTIPICKLIST, false, null),
                    "ANY;THING").isSuccess());
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

        @Test void missingDomain_returnsFailure() {
            assertTrue(engine.coerce(field(FieldType.EMAIL, false), "user@host").isFailure());
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

        @Test void missingScheme_returnsFailure() {
            assertTrue(engine.coerce(field(FieldType.URL, false), "example.com").isFailure());
        }
    }

    // ── PHONE ─────────────────────────────────────────────────────────────────

    @Nested @DisplayName("PHONE")
    class PhoneField {
        @Test void formattedPhone_isNormalized() {
            var r = engine.coerce(field(FieldType.PHONE, false), "+1 (555) 123-4567");
            assertEquals("+15551234567", value(r));
        }

        @Test void plainDigits_passThrough() {
            var r = engine.coerce(field(FieldType.PHONE, false), "5551234567");
            assertEquals("5551234567", value(r));
        }

        @Test void lettersAreStripped() {
            var r = engine.coerce(field(FieldType.PHONE, false), "555-CALL-NOW1");
            assertEquals("5551", value(r));
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

        @Test void uuidInstance_returnsUUID() {
            var r = engine.coerce(field(FieldType.LOOKUP, false), UUID.randomUUID());
            assertInstanceOf(UUID.class, value(r));
        }

        @Test void invalidUuid_returnsFailure() {
            assertTrue(engine.coerce(field(FieldType.LOOKUP, false), "not-uuid").isFailure());
        }
    }

    // ── MASTER_DETAIL ────────────────────────────────────────────────────────

    @Nested @DisplayName("MASTER_DETAIL")
    class MasterDetailField {
        @Test void validUuid_returnsUUID() {
            var r = engine.coerce(field(FieldType.MASTER_DETAIL, false),
                    UUID.randomUUID().toString());
            assertInstanceOf(UUID.class, value(r));
        }

        @Test void invalidUuid_returnsFailure() {
            assertTrue(engine.coerce(field(FieldType.MASTER_DETAIL, false), "12345").isFailure());
        }
    }

    // ── Read-only types ───────────────────────────────────────────────────────

    @ParameterizedTest
    @DisplayName("AUTO_NUMBER and FORMULA always reject input")
    @EnumSource(value = FieldType.class, names = {"AUTO_NUMBER", "FORMULA"})
    void readOnly_alwaysRejectsInput(FieldType type) {
        assertTrue(engine.coerce(field(type, false), "any").isFailure());
    }

    @ParameterizedTest
    @DisplayName("AUTO_NUMBER and FORMULA reject input even when required")
    @EnumSource(value = FieldType.class, names = {"AUTO_NUMBER", "FORMULA"})
    void readOnly_rejectsInputWhenRequired(FieldType type) {
        assertTrue(engine.coerce(field(type, true), "any").isFailure());
    }
}
