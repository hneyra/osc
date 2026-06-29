package dev.osc.metadata;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable snapshot of a record type within an object (ADR-006).
 *
 * <p>A {@code NULL} {@code record_type_id} on a {@code record} row means "the object's single
 * default record type" — objects that never define record types behave exactly as before.
 *
 * <p>Shape is defined by {@code docs/contracts/metadata-record-type-schema.json}.
 */
public record RecordTypeDefinition(
        UUID id,
        UUID tenantId,
        UUID objectId,
        String apiName,
        String label,
        boolean isDefault,
        boolean isActive,
        Instant createdAt
) {
    public RecordTypeDefinition {
        if (tenantId == null) throw new IllegalArgumentException("tenantId must not be null");
        if (objectId == null) throw new IllegalArgumentException("objectId must not be null");
        if (apiName == null || apiName.isBlank()) throw new IllegalArgumentException("apiName must not be blank");
        if (label == null || label.isBlank()) throw new IllegalArgumentException("label must not be blank");
    }
}
