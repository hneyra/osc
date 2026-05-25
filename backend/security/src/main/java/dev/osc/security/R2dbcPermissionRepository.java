package dev.osc.security;

import io.r2dbc.spi.ConnectionFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * R2DBC implementation of PermissionRepository.
 *
 * Both queries join through md_user_permission_set to aggregate permissions across
 * all permission sets assigned to the user. SQL is fully parameterized — no interpolation.
 */
@Repository
public class R2dbcPermissionRepository implements PermissionRepository {

    private final DatabaseClient db;

    public R2dbcPermissionRepository(ConnectionFactory connectionFactory) {
        this.db = DatabaseClient.create(connectionFactory);
    }

    @Override
    public Flux<ObjectPermission> findObjectPermissionsForUser(UUID tenantId, UUID userId, String objectApiName) {
        return db.sql("""
                SELECT op.object_id, op.permission_set_id,
                       op.can_read, op.can_create, op.can_edit, op.can_delete
                FROM   md_object_permission op
                JOIN   md_user_permission_set ups
                       ON ups.permission_set_id = op.permission_set_id
                       AND ups.tenant_id = op.tenant_id
                JOIN   md_object obj
                       ON obj.id = op.object_id
                       AND obj.tenant_id = op.tenant_id
                WHERE  op.tenant_id        = :tenantId
                  AND  ups.user_id         = :userId
                  AND  obj.api_name        = :objectApiName
                """)
                .bind("tenantId", tenantId)
                .bind("userId", userId)
                .bind("objectApiName", objectApiName)
                .map(row -> new ObjectPermission(
                        row.get("object_id", UUID.class),
                        row.get("permission_set_id", UUID.class),
                        Boolean.TRUE.equals(row.get("can_read", Boolean.class)),
                        Boolean.TRUE.equals(row.get("can_create", Boolean.class)),
                        Boolean.TRUE.equals(row.get("can_edit", Boolean.class)),
                        Boolean.TRUE.equals(row.get("can_delete", Boolean.class))
                ))
                .all();
    }

    @Override
    public Flux<FieldPermission> findFieldPermissionsForUser(UUID tenantId, UUID userId, String objectApiName) {
        return db.sql("""
                SELECT fp.field_id, fp.permission_set_id, fp.field_api_name,
                       fp.can_read, fp.can_edit
                FROM   md_field_permission fp
                JOIN   md_user_permission_set ups
                       ON ups.permission_set_id = fp.permission_set_id
                       AND ups.tenant_id = fp.tenant_id
                JOIN   md_field f
                       ON f.id = fp.field_id
                       AND f.tenant_id = fp.tenant_id
                JOIN   md_object obj
                       ON obj.id = f.object_id
                       AND obj.tenant_id = fp.tenant_id
                WHERE  fp.tenant_id  = :tenantId
                  AND  ups.user_id   = :userId
                  AND  obj.api_name  = :objectApiName
                """)
                .bind("tenantId", tenantId)
                .bind("userId", userId)
                .bind("objectApiName", objectApiName)
                .map(row -> new FieldPermission(
                        row.get("field_id", UUID.class),
                        row.get("permission_set_id", UUID.class),
                        row.get("field_api_name", String.class),
                        Boolean.TRUE.equals(row.get("can_read", Boolean.class)),
                        Boolean.TRUE.equals(row.get("can_edit", Boolean.class))
                ))
                .all();
    }
}
