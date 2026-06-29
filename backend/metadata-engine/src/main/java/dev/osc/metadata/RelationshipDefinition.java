package dev.osc.metadata;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable snapshot of a relationship between two objects (ADR-006).
 *
 * <p>For LOOKUP and MASTER_DETAIL relationships, {@code fieldId} is the lookup field on the
 * child object; {@code junctionObjectId} is null. For MANY_TO_MANY, {@code junctionObjectId}
 * is the auto-created junction object; {@code fieldId} is null.
 *
 * <p>Shape is defined by {@code docs/contracts/metadata-relationship-schema.json}.
 */
public record RelationshipDefinition(
        UUID id,
        UUID tenantId,
        UUID childObjectId,
        UUID parentObjectId,
        String relationshipType,
        UUID fieldId,
        UUID junctionObjectId,
        String onDelete,
        Instant createdAt
) {
    public RelationshipDefinition {
        if (tenantId == null) throw new IllegalArgumentException("tenantId must not be null");
        if (childObjectId == null) throw new IllegalArgumentException("childObjectId must not be null");
        if (parentObjectId == null) throw new IllegalArgumentException("parentObjectId must not be null");
        if (relationshipType == null || relationshipType.isBlank())
            throw new IllegalArgumentException("relationshipType must not be blank");
        if (!relationshipType.equals("LOOKUP")
                && !relationshipType.equals("MASTER_DETAIL")
                && !relationshipType.equals("MANY_TO_MANY"))
            throw new IllegalArgumentException("relationshipType must be LOOKUP, MASTER_DETAIL, or MANY_TO_MANY");
    }
}
