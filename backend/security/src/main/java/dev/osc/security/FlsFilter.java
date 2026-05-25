package dev.osc.security;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Field-Level Security filter.
 * Strips fields the current user is not permitted to read from record maps.
 *
 * System fields (id, objectId, ownerId, createdAt, updatedAt) always pass through —
 * they are structural metadata, not business data.
 *
 * Empty allowedFields means no FLS rules are configured → record passes unchanged.
 */
@Component
public class FlsFilter {

    private static final Set<String> SYSTEM_FIELDS = Set.of(
            "id", "objectId", "ownerId", "createdAt", "updatedAt"
    );

    private final PermissionChecker permissionChecker;

    public FlsFilter(PermissionChecker permissionChecker) {
        this.permissionChecker = permissionChecker;
    }

    public Mono<Map<String, Object>> apply(
            Map<String, Object> record,
            UUID tenantId,
            UUID userId,
            String objectApiName) {

        return permissionChecker.allowedReadFields(tenantId, userId, objectApiName)
                .map(allowedFields -> {
                    if (allowedFields.isEmpty()) {
                        return record;
                    }
                    Map<String, Object> filtered = new LinkedHashMap<>();
                    for (Map.Entry<String, Object> entry : record.entrySet()) {
                        if (SYSTEM_FIELDS.contains(entry.getKey())
                                || allowedFields.contains(entry.getKey())) {
                            filtered.put(entry.getKey(), entry.getValue());
                        }
                    }
                    return (Map<String, Object>) filtered;
                });
    }
}
