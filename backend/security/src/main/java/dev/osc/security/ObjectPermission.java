package dev.osc.security;

import java.util.UUID;

/** Aggregated object-level permission derived from a permission set. */
public record ObjectPermission(
        UUID objectId,
        UUID permissionSetId,
        boolean canRead,
        boolean canCreate,
        boolean canEdit,
        boolean canDelete
) {}
