package dev.osc.security;

import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.UUID;

/**
 * Primary port — answers permission questions for a given user.
 * All methods are reactive (Mono) to avoid blocking the event loop.
 *
 * Union semantics: a user has a permission if ANY of their permission sets grants it.
 */
public interface PermissionChecker {

    Mono<Boolean> canRead(UUID tenantId, UUID userId, String objectApiName);

    Mono<Boolean> canCreate(UUID tenantId, UUID userId, String objectApiName);

    Mono<Boolean> canEdit(UUID tenantId, UUID userId, String objectApiName);

    Mono<Boolean> canDelete(UUID tenantId, UUID userId, String objectApiName);

    /**
     * Returns the set of field API names the user is allowed to read.
     * Empty set means no field permissions are defined (caller should treat as unrestricted).
     */
    Mono<Set<String>> allowedReadFields(UUID tenantId, UUID userId, String objectApiName);
}
