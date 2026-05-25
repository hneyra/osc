package dev.osc.security;

import reactor.core.publisher.Flux;

import java.util.UUID;

/** Secondary port — loads permission data from the database. */
public interface PermissionRepository {

    /**
     * Returns all ObjectPermissions for a user on a given object across all their permission sets.
     * Union semantics: multiple permission sets are returned individually; caller aggregates.
     */
    Flux<ObjectPermission> findObjectPermissionsForUser(UUID tenantId, UUID userId, String objectApiName);

    /**
     * Returns all FieldPermissions for a user on a given object across all their permission sets.
     */
    Flux<FieldPermission> findFieldPermissionsForUser(UUID tenantId, UUID userId, String objectApiName);
}
