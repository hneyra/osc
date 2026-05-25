package dev.osc.security;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.UUID;

/**
 * Default implementation of PermissionChecker.
 * Applies union semantics: a permission is granted if ANY permission set grants it.
 */
@Component
public class DefaultPermissionChecker implements PermissionChecker {

    private final PermissionRepository permissionRepository;

    public DefaultPermissionChecker(PermissionRepository permissionRepository) {
        this.permissionRepository = permissionRepository;
    }

    @Override
    public Mono<Boolean> canRead(UUID tenantId, UUID userId, String objectApiName) {
        return anyGranted(tenantId, userId, objectApiName, ObjectPermission::canRead);
    }

    @Override
    public Mono<Boolean> canCreate(UUID tenantId, UUID userId, String objectApiName) {
        return anyGranted(tenantId, userId, objectApiName, ObjectPermission::canCreate);
    }

    @Override
    public Mono<Boolean> canEdit(UUID tenantId, UUID userId, String objectApiName) {
        return anyGranted(tenantId, userId, objectApiName, ObjectPermission::canEdit);
    }

    @Override
    public Mono<Boolean> canDelete(UUID tenantId, UUID userId, String objectApiName) {
        return anyGranted(tenantId, userId, objectApiName, ObjectPermission::canDelete);
    }

    @Override
    public Mono<Set<String>> allowedReadFields(UUID tenantId, UUID userId, String objectApiName) {
        return permissionRepository
                .findFieldPermissionsForUser(tenantId, userId, objectApiName)
                .filter(FieldPermission::canRead)
                .map(FieldPermission::fieldApiName)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private Mono<Boolean> anyGranted(UUID tenantId, UUID userId, String objectApiName,
                                      java.util.function.Predicate<ObjectPermission> permission) {
        return permissionRepository
                .findObjectPermissionsForUser(tenantId, userId, objectApiName)
                .any(permission::test)
                .defaultIfEmpty(false);
    }
}
