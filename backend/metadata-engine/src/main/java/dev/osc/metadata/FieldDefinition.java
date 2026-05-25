package dev.osc.metadata;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable snapshot of a field definition within an object.
 * storageKey is the JSONB key or column name used to persist the value.
 * config holds field-type-specific settings serialised as JSON (e.g. picklistValues).
 */
public record FieldDefinition(
        UUID id,
        UUID tenantId,
        UUID objectId,
        String apiName,
        String label,
        FieldType fieldType,
        StorageKind storageKind,
        String storageKey,
        boolean isRequired,
        boolean isCustom,
        String config,
        Instant createdAt,
        Instant updatedAt
) {
    public FieldDefinition {
        if (tenantId == null) throw new IllegalArgumentException("tenantId must not be null");
        if (objectId == null) throw new IllegalArgumentException("objectId must not be null");
        if (apiName == null || apiName.isBlank()) throw new IllegalArgumentException("apiName must not be blank");
        if (fieldType == null) throw new IllegalArgumentException("fieldType must not be null");
        if (storageKind == null) throw new IllegalArgumentException("storageKind must not be null");
    }
}
