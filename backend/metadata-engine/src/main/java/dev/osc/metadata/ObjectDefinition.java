package dev.osc.metadata;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable snapshot of an object/entity definition retrieved from the metadata store.
 * Designed as a value type — equals/hashCode based on all fields.
 */
public record ObjectDefinition(
        UUID id,
        UUID tenantId,
        String apiName,
        String label,
        String labelPlural,
        boolean isCustom,
        Instant createdAt,
        Instant updatedAt
) {
    public ObjectDefinition {
        if (tenantId == null) throw new IllegalArgumentException("tenantId must not be null");
        if (apiName == null || apiName.isBlank()) throw new IllegalArgumentException("apiName must not be blank");
    }
}
