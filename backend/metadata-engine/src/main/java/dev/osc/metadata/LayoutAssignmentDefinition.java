package dev.osc.metadata;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable snapshot of a layout assignment (ADR-006).
 *
 * <p>Maps a page layout ({@code layoutId}) to a specific record type and/or permission set.
 * Both {@code recordTypeId} and {@code permissionSetId} are nullable; {@code NULL} means
 * "applies to all" in that dimension.
 *
 * <p>Resolution order (most specific wins):
 * <ol>
 *   <li>(recordTypeId, permissionSetId) — both specified</li>
 *   <li>(recordTypeId, null) — record-type match, any profile</li>
 *   <li>(null, permissionSetId) — any record type, profile match</li>
 *   <li>(null, null) — object default</li>
 * </ol>
 *
 * <p>Shape is defined by {@code docs/contracts/metadata-layout-assignment-schema.json}.
 */
public record LayoutAssignmentDefinition(
        UUID id,
        UUID tenantId,
        UUID layoutId,
        UUID recordTypeId,
        UUID permissionSetId,
        Instant createdAt
) {
    public LayoutAssignmentDefinition {
        if (tenantId == null) throw new IllegalArgumentException("tenantId must not be null");
        if (layoutId == null) throw new IllegalArgumentException("layoutId must not be null");
    }
}
