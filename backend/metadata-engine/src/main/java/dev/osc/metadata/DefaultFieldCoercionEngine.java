package dev.osc.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class DefaultFieldCoercionEngine implements FieldCoercionEngine {

    private static final Pattern EMAIL_RE =
            Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public CoercionResult coerce(FieldDefinition field, Object rawValue) {
        if (rawValue == null) {
            return field.isRequired()
                    ? CoercionResult.failure("Field '%s' is required".formatted(field.apiName()))
                    : CoercionResult.success(null);
        }
        return switch (field.fieldType()) {
            case TEXT, TEXTAREA   -> coerceText(field, rawValue);
            case NUMBER, PERCENT  -> coerceNumber(field, rawValue, null);
            case CURRENCY         -> coerceNumber(field, rawValue, 2);
            case DATE             -> coerceDate(rawValue);
            case DATETIME         -> coerceDatetime(rawValue);
            case TIME             -> coerceTime(rawValue);
            case BOOLEAN          -> coerceBoolean(rawValue);
            case PICKLIST         -> coercePicklist(field, rawValue);
            case MULTIPICKLIST    -> coerceMultiPicklist(field, rawValue);
            case EMAIL            -> coerceEmail(rawValue);
            case URL              -> coerceUrl(rawValue);
            case PHONE            -> coercePhone(rawValue);
            case LOOKUP, MASTER_DETAIL -> coerceLookup(rawValue);
            case AUTO_NUMBER, FORMULA, ROLLUP ->
                    CoercionResult.failure("Field '%s' is read-only".formatted(field.apiName()));
        };
    }

    private CoercionResult coerceText(FieldDefinition field, Object raw) {
        String value = raw.toString();
        Integer maxLength = intConfig(field.config(), "maxLength");
        if (maxLength != null && value.length() > maxLength) {
            return CoercionResult.failure(
                    "Value for field '%s' exceeds max length %d".formatted(field.apiName(), maxLength));
        }
        return CoercionResult.success(value);
    }

    private CoercionResult coerceNumber(FieldDefinition field, Object raw, Integer defaultScale) {
        BigDecimal value;
        try {
            value = new BigDecimal(raw.toString());
        } catch (NumberFormatException e) {
            return CoercionResult.failure("Invalid number: '%s'".formatted(raw));
        }
        Integer scale = intConfig(field.config(), "scale");
        if (scale == null) scale = defaultScale;
        if (scale != null) {
            value = value.setScale(scale, RoundingMode.HALF_UP);
        }
        Integer precision = intConfig(field.config(), "precision");
        if (precision != null && value.precision() > precision) {
            return CoercionResult.failure(
                    "Value '%s' exceeds precision %d for field '%s'"
                            .formatted(raw, precision, field.apiName()));
        }
        return CoercionResult.success(value);
    }

    private CoercionResult coerceDate(Object raw) {
        try {
            return CoercionResult.success(LocalDate.parse(raw.toString()));
        } catch (DateTimeParseException e) {
            return CoercionResult.failure("Invalid date (expected YYYY-MM-DD): '%s'".formatted(raw));
        }
    }

    private CoercionResult coerceDatetime(Object raw) {
        try {
            return CoercionResult.success(OffsetDateTime.parse(raw.toString()));
        } catch (DateTimeParseException e) {
            return CoercionResult.failure("Invalid datetime (expected ISO-8601): '%s'".formatted(raw));
        }
    }

    private CoercionResult coerceTime(Object raw) {
        try {
            return CoercionResult.success(LocalTime.parse(raw.toString()));
        } catch (DateTimeParseException e) {
            return CoercionResult.failure("Invalid time (expected HH:mm:ss): '%s'".formatted(raw));
        }
    }

    private CoercionResult coerceBoolean(Object raw) {
        String s = raw.toString().trim().toLowerCase();
        return switch (s) {
            case "true",  "1" -> CoercionResult.success(Boolean.TRUE);
            case "false", "0" -> CoercionResult.success(Boolean.FALSE);
            default -> CoercionResult.failure("Invalid boolean value: '%s'".formatted(raw));
        };
    }

    private CoercionResult coercePicklist(FieldDefinition field, Object raw) {
        String value = raw.toString();
        Set<String> allowed = parsePicklistValues(field.config());
        if (!allowed.isEmpty() && !allowed.contains(value)) {
            return CoercionResult.failure(
                    "Value '%s' is not in picklist for field '%s'".formatted(value, field.apiName()));
        }
        return CoercionResult.success(value);
    }

    private CoercionResult coerceMultiPicklist(FieldDefinition field, Object raw) {
        Set<String> allowed = parsePicklistValues(field.config());
        List<String> values = new ArrayList<>();
        if (raw instanceof Collection<?> collection) {
            for (Object element : collection) {
                if (element == null) {
                    return CoercionResult.failure(
                            "Null entry in multipicklist for field '%s'".formatted(field.apiName()));
                }
                values.add(element.toString().trim());
            }
        } else {
            for (String v : raw.toString().split(";")) {
                values.add(v.trim());
            }
        }
        for (String value : values) {
            if (!allowed.isEmpty() && !allowed.contains(value)) {
                return CoercionResult.failure(
                        "Value '%s' is not in picklist for field '%s'".formatted(value, field.apiName()));
            }
        }
        return CoercionResult.success(String.join(";", values));
    }

    private CoercionResult coerceEmail(Object raw) {
        String value = raw.toString();
        return EMAIL_RE.matcher(value).matches()
                ? CoercionResult.success(value)
                : CoercionResult.failure("Invalid email: '%s'".formatted(value));
    }

    private CoercionResult coerceUrl(Object raw) {
        try {
            URI uri = new URI(raw.toString());
            if (uri.getScheme() == null) throw new IllegalArgumentException("no scheme");
            return CoercionResult.success(raw.toString());
        } catch (Exception e) {
            return CoercionResult.failure("Invalid URL: '%s'".formatted(raw));
        }
    }

    private CoercionResult coercePhone(Object raw) {
        return CoercionResult.success(raw.toString().replaceAll("[^0-9+]", ""));
    }

    private CoercionResult coerceLookup(Object raw) {
        try {
            return CoercionResult.success(UUID.fromString(raw.toString()));
        } catch (IllegalArgumentException e) {
            return CoercionResult.failure("Invalid UUID for lookup: '%s'".formatted(raw));
        }
    }

    private Set<String> parsePicklistValues(String config) {
        if (config == null || config.isBlank()) return Set.of();
        try {
            JsonNode root = mapper.readTree(config);
            JsonNode values = root.path("picklistValues");
            Set<String> result = new HashSet<>();
            for (JsonNode node : values) {
                result.add(node.path("value").asText());
            }
            return result;
        } catch (Exception e) {
            return Set.of();
        }
    }

    /** Reads an integer setting (e.g. maxLength, precision, scale) from the field's JSON config, or null. */
    private Integer intConfig(String config, String key) {
        if (config == null || config.isBlank()) return null;
        try {
            JsonNode node = mapper.readTree(config).path(key);
            return node.isNumber() ? node.intValue() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
