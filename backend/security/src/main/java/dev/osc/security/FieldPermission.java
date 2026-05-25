package dev.osc.security;

import java.util.UUID;

/** Field-level permission from a permission set. */
public record FieldPermission(
        UUID fieldId,
        UUID permissionSetId,
        String fieldApiName,
        boolean canRead,
        boolean canEdit
) {}
