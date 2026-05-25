package dev.osc.persistence;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable snapshot of a row in the universal `record` table.
 * data holds all JSONB-stored fields as a typed map.
 */
public record RecordEntity(
        UUID id,
        UUID tenantId,
        UUID objectId,
        String name,
        UUID ownerId,
        Map<String, Object> data,
        Instant createdAt,
        Instant updatedAt
) {}
