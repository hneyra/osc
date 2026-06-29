package dev.osc.persistence;

import dev.osc.metadata.*;
import io.r2dbc.spi.Row;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * R2DBC adapter for MetadataRepository.
 *
 * Each query calls set_config('app.current_tenant', ...) before the SELECT to
 * activate PostgreSQL RLS. Application-level WHERE tenant_id = :tenantId provides
 * a second defence layer that is independent of the RLS setting.
 *
 * Phase 1 limitation: set_config uses session scope (is_local=false).
 * Phase 4 will replace this with transaction-scoped SET LOCAL inside a
 * proper reactive transaction.
 */
@Repository
public class R2dbcMetadataRepository implements MetadataRepository {

    private final DatabaseClient client;

    public R2dbcMetadataRepository(DatabaseClient client) {
        this.client = client;
    }

    @Override
    public Mono<ObjectDefinition> findObject(UUID tenantId, String apiName) {
        return activateTenant(tenantId)
                .then(client.sql("""
                        SELECT id, tenant_id, api_name, label, label_plural,
                               is_custom, created_at, updated_at
                        FROM md_object
                        WHERE tenant_id = :tenantId AND api_name = :apiName
                        """)
                        .bind("tenantId", tenantId)
                        .bind("apiName", apiName)
                        .map((row, meta) -> toObjectDefinition(row))
                        .one());
    }

    @Override
    public Flux<FieldDefinition> findFields(UUID tenantId, UUID objectId) {
        return activateTenant(tenantId)
                .thenMany(client.sql("""
                        SELECT id, tenant_id, object_id, api_name, label,
                               field_type, storage_kind, storage_key,
                               is_required, is_custom, config::text,
                               created_at, updated_at
                        FROM md_field
                        WHERE tenant_id = :tenantId AND object_id = :objectId
                        ORDER BY api_name
                        """)
                        .bind("tenantId", tenantId)
                        .bind("objectId", objectId)
                        .map((row, meta) -> toFieldDefinition(row))
                        .all());
    }

    // ── ADR-006: Extended Metadata ───────────────────────────────────────────

    @Override
    public Flux<RelationshipDefinition> findRelationships(UUID tenantId, UUID objectId) {
        return activateTenant(tenantId)
                .thenMany(client.sql("""
                        SELECT id, tenant_id, relationship_type,
                               child_object_id, parent_object_id,
                               field_id, junction_object_id, on_delete, created_at
                        FROM md_relationship
                        WHERE tenant_id = :tenantId
                          AND (child_object_id = :objectId OR parent_object_id = :objectId)
                        ORDER BY created_at
                        """)
                        .bind("tenantId", tenantId)
                        .bind("objectId", objectId)
                        .map((row, meta) -> toRelationshipDefinition(row))
                        .all());
    }

    @Override
    public Flux<RecordTypeDefinition> findRecordTypes(UUID tenantId, UUID objectId) {
        return activateTenant(tenantId)
                .thenMany(client.sql("""
                        SELECT id, tenant_id, object_id, api_name, label,
                               is_default, is_active, created_at
                        FROM md_record_type
                        WHERE tenant_id = :tenantId AND object_id = :objectId
                        ORDER BY is_default DESC, api_name
                        """)
                        .bind("tenantId", tenantId)
                        .bind("objectId", objectId)
                        .map((row, meta) -> toRecordTypeDefinition(row))
                        .all());
    }

    @Override
    public Flux<LayoutAssignmentDefinition> findLayoutAssignments(UUID tenantId, UUID objectId) {
        return activateTenant(tenantId)
                .thenMany(client.sql("""
                        SELECT la.id, la.tenant_id, la.layout_id,
                               la.record_type_id, la.permission_set_id, la.created_at
                        FROM md_layout_assignment la
                        JOIN md_layout l ON l.id = la.layout_id
                        WHERE la.tenant_id = :tenantId
                          AND l.object_id  = :objectId
                        """)
                        .bind("tenantId", tenantId)
                        .bind("objectId", objectId)
                        .map((row, meta) -> toLayoutAssignmentDefinition(row))
                        .all());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private Mono<Void> activateTenant(UUID tenantId) {
        return client.sql("SELECT set_config('app.current_tenant', :tenantId, false)")
                .bind("tenantId", tenantId.toString())
                .fetch()
                .rowsUpdated()
                .then();
    }

    private ObjectDefinition toObjectDefinition(Row row) {
        return new ObjectDefinition(
                row.get("id", UUID.class),
                row.get("tenant_id", UUID.class),
                row.get("api_name", String.class),
                row.get("label", String.class),
                row.get("label_plural", String.class),
                Boolean.TRUE.equals(row.get("is_custom", Boolean.class)),
                row.get("created_at", Instant.class),
                row.get("updated_at", Instant.class)
        );
    }

    private FieldDefinition toFieldDefinition(Row row) {
        return new FieldDefinition(
                row.get("id", UUID.class),
                row.get("tenant_id", UUID.class),
                row.get("object_id", UUID.class),
                row.get("api_name", String.class),
                row.get("label", String.class),
                FieldType.valueOf(row.get("field_type", String.class)),
                StorageKind.valueOf(row.get("storage_kind", String.class)),
                row.get("storage_key", String.class),
                Boolean.TRUE.equals(row.get("is_required", Boolean.class)),
                Boolean.TRUE.equals(row.get("is_custom", Boolean.class)),
                row.get("config", String.class),
                row.get("created_at", Instant.class),
                row.get("updated_at", Instant.class)
        );
    }

    private RelationshipDefinition toRelationshipDefinition(Row row) {
        return new RelationshipDefinition(
                row.get("id", UUID.class),
                row.get("tenant_id", UUID.class),
                row.get("child_object_id", UUID.class),
                row.get("parent_object_id", UUID.class),
                row.get("relationship_type", String.class),
                row.get("field_id", UUID.class),
                row.get("junction_object_id", UUID.class),
                row.get("on_delete", String.class),
                row.get("created_at", Instant.class)
        );
    }

    private RecordTypeDefinition toRecordTypeDefinition(Row row) {
        return new RecordTypeDefinition(
                row.get("id", UUID.class),
                row.get("tenant_id", UUID.class),
                row.get("object_id", UUID.class),
                row.get("api_name", String.class),
                row.get("label", String.class),
                Boolean.TRUE.equals(row.get("is_default", Boolean.class)),
                Boolean.TRUE.equals(row.get("is_active", Boolean.class)),
                row.get("created_at", Instant.class)
        );
    }

    private LayoutAssignmentDefinition toLayoutAssignmentDefinition(Row row) {
        return new LayoutAssignmentDefinition(
                row.get("id", UUID.class),
                row.get("tenant_id", UUID.class),
                row.get("layout_id", UUID.class),
                row.get("record_type_id", UUID.class),
                row.get("permission_set_id", UUID.class),
                row.get("created_at", Instant.class)
        );
    }
}
