package dev.osc.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.osc.metadata.TenantContext;
import io.r2dbc.spi.Row;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * R2DBC adapter for RecordRepository.
 *
 * Tenant ID is always read from Reactor Context (never a parameter).
 * set_config + explicit WHERE tenant_id provides dual-layer tenant isolation.
 * All SQL uses parameterized binds — no string interpolation — preventing SQL injection.
 */
@Repository
public class R2dbcRecordRepository implements RecordRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final DatabaseClient client;
    private final ObjectMapper objectMapper;

    public R2dbcRecordRepository(DatabaseClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<RecordEntity> insert(RecordInsertCommand cmd) {
        return resolveTenantId().flatMap(tenantId -> {
            var spec = client.sql("""
                    INSERT INTO record (tenant_id, object_id, name, owner_id, data)
                    VALUES (:tenantId, :objectId, :name, :ownerId, CAST(:data AS jsonb))
                    RETURNING id, tenant_id, object_id, name, owner_id, data::text, created_at, updated_at
                    """)
                    .bind("tenantId", tenantId)
                    .bind("objectId", cmd.objectId())
                    .bind("data", serializeData(cmd.data()));
            spec = cmd.name() != null ? spec.bind("name", cmd.name()) : spec.bindNull("name", String.class);
            spec = cmd.ownerId() != null ? spec.bind("ownerId", cmd.ownerId()) : spec.bindNull("ownerId", UUID.class);
            return spec.map((row, meta) -> toEntity(row)).one();
        });
    }

    @Override
    public Mono<RecordEntity> findById(UUID id) {
        return resolveTenantId().flatMap(tenantId ->
                client.sql("""
                        SELECT id, tenant_id, object_id, name, owner_id, data::text, created_at, updated_at
                        FROM record
                        WHERE id = :id AND tenant_id = :tenantId
                        """)
                        .bind("id", id)
                        .bind("tenantId", tenantId)
                        .map((row, meta) -> toEntity(row))
                        .one()
        );
    }

    @Override
    public Flux<RecordEntity> findByObjectId(UUID objectId, PageRequest page) {
        return resolveTenantId().flatMapMany(tenantId ->
                client.sql("""
                        SELECT id, tenant_id, object_id, name, owner_id, data::text, created_at, updated_at
                        FROM record
                        WHERE object_id = :objectId AND tenant_id = :tenantId
                        ORDER BY created_at DESC
                        LIMIT :limit OFFSET :offset
                        """)
                        .bind("objectId", objectId)
                        .bind("tenantId", tenantId)
                        .bind("limit", page.size())
                        .bind("offset", page.offset())
                        .map((row, meta) -> toEntity(row))
                        .all()
        );
    }

    @Override
    public Flux<RecordEntity> findByField(UUID objectId, String fieldKey, Object value) {
        return resolveTenantId().flatMapMany(tenantId -> {
            String valStr = value != null ? value.toString() : null;
            return client.sql("""
                    SELECT id, tenant_id, object_id, name, owner_id, data::text, created_at, updated_at
                    FROM record
                    WHERE object_id = :objectId AND tenant_id = :tenantId AND data ->> :fieldKey = :value
                    """)
                    .bind("objectId", objectId)
                    .bind("tenantId", tenantId)
                    .bind("fieldKey", fieldKey)
                    .bind("value", valStr)
                    .map((row, meta) -> toEntity(row))
                    .all();
        });
    }

    @Override
    public Mono<RecordEntity> update(RecordUpdateCommand cmd) {
        return resolveTenantId().flatMap(tenantId -> {
            boolean hasPatch = cmd.dataPatch() != null && !cmd.dataPatch().isEmpty();
            var spec = client.sql("""
                    UPDATE record
                    SET data       = CASE WHEN :hasPatch THEN data || CAST(:dataPatch AS jsonb) ELSE data END,
                        name       = COALESCE(:name, name),
                        owner_id   = COALESCE(:ownerId, owner_id),
                        updated_at = now()
                    WHERE id = :id AND tenant_id = :tenantId
                    RETURNING id, tenant_id, object_id, name, owner_id, data::text, created_at, updated_at
                    """)
                    .bind("id", cmd.id())
                    .bind("tenantId", tenantId)
                    .bind("hasPatch", hasPatch)
                    .bind("dataPatch", hasPatch ? serializeData(cmd.dataPatch()) : "{}");
            spec = cmd.name() != null ? spec.bind("name", cmd.name()) : spec.bindNull("name", String.class);
            spec = cmd.ownerId() != null ? spec.bind("ownerId", cmd.ownerId()) : spec.bindNull("ownerId", UUID.class);
            return spec.map((row, meta) -> toEntity(row)).one();
        });
    }

    @Override
    public Mono<Void> delete(UUID id) {
        return resolveTenantId().flatMap(tenantId ->
                client.sql("DELETE FROM record WHERE id = :id AND tenant_id = :tenantId")
                        .bind("id", id)
                        .bind("tenantId", tenantId)
                        .fetch()
                        .rowsUpdated()
                        .then()
        );
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Mono<UUID> resolveTenantId() {
        return Mono.deferContextual(ctx -> {
            UUID tenantId = UUID.fromString((String) ctx.get(TenantContext.TENANT_ID_KEY));
            return activateTenant(tenantId).thenReturn(tenantId);
        });
    }

    private Mono<Void> activateTenant(UUID tenantId) {
        return client.sql("SELECT set_config('app.current_tenant', :tenantId, false)")
                .bind("tenantId", tenantId.toString())
                .fetch()
                .rowsUpdated()
                .then();
    }

    private RecordEntity toEntity(Row row) {
        return new RecordEntity(
                row.get("id", UUID.class),
                row.get("tenant_id", UUID.class),
                row.get("object_id", UUID.class),
                row.get("name", String.class),
                row.get("owner_id", UUID.class),
                deserializeData(row.get("data", String.class)),
                row.get("created_at", Instant.class),
                row.get("updated_at", Instant.class)
        );
    }

    private String serializeData(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize record data to JSON", e);
        }
    }

    private Map<String, Object> deserializeData(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot deserialize record data from JSON", e);
        }
    }
}
