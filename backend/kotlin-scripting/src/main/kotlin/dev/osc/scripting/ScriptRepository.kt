package dev.osc.scripting

import io.r2dbc.spi.Row
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import reactor.core.publisher.Mono
import java.time.Instant
import java.util.UUID

/**
 * Repository for managing md_script entities via DatabaseClient.
 */
@Repository
class ScriptRepository(private val client: DatabaseClient) {

    private fun mapRow(row: Row): Script {
        return Script(
            id = row.get("id", UUID::class.java)!!,
            tenantId = row.get("tenant_id", UUID::class.java)!!,
            objectId = row.get("object_id", UUID::class.java)!!,
            kind = row.get("kind", String::class.java)!!,
            triggerEvent = row.get("trigger_event", String::class.java),
            invocableName = row.get("invocable_name", String::class.java),
            scheduleCron = row.get("schedule_cron", String::class.java),
            source = row.get("source", String::class.java)!!,
            isActive = java.lang.Boolean.TRUE == row.get("is_active", java.lang.Boolean::class.java),
            compiledAt = row.get("compiled_at", Instant::class.java),
            compileErrorsJson = row.get("compile_errors", String::class.java) ?: "[]",
            timeoutSeconds = row.get("timeout_seconds", java.lang.Integer::class.java)?.toInt() ?: 5,
            generatedByAi = java.lang.Boolean.TRUE == row.get("generated_by_ai", java.lang.Boolean::class.java),
            createdAt = row.get("created_at", Instant::class.java)!!,
            updatedAt = row.get("updated_at", Instant::class.java)!!
        )
    }

    fun findById(tenantId: UUID, id: UUID): Mono<Script> {
        return activateTenant(tenantId)
            .then(client.sql("""
                SELECT id, tenant_id, object_id, kind, trigger_event, invocable_name,
                       schedule_cron, source, is_active, compiled_at, compile_errors,
                       timeout_seconds, generated_by_ai, created_at, updated_at
                FROM md_script
                WHERE tenant_id = :tenantId AND id = :id
                """)
                .bind("tenantId", tenantId)
                .bind("id", id)
                .map { row, _ -> mapRow(row) }
                .one())
    }

    fun save(script: Script): Mono<Script> {
        return activateTenant(script.tenantId)
            .then(Mono.defer {
                var spec = client.sql("""
                    INSERT INTO md_script (
                        id, tenant_id, object_id, kind, trigger_event, invocable_name,
                        schedule_cron, source, is_active, compiled_at, compile_errors,
                        timeout_seconds, generated_by_ai, created_at, updated_at
                    ) VALUES (
                        :id, :tenantId, :objectId, :kind, :triggerEvent, :invocableName,
                        :scheduleCron, :source, :isActive, :compiledAt, :compileErrors::jsonb,
                        :timeoutSeconds, :generatedByAi, :createdAt, :updatedAt
                    )
                    ON CONFLICT (id) DO UPDATE SET
                        source = :source,
                        is_active = :isActive,
                        compiled_at = :compiledAt,
                        compile_errors = :compileErrors::jsonb,
                        timeout_seconds = :timeoutSeconds,
                        updated_at = :updatedAt
                    """)
                    .bind("id", script.id)
                    .bind("tenantId", script.tenantId)
                    .bind("objectId", script.objectId)
                    .bind("kind", script.kind)

                spec = if (script.triggerEvent != null) spec.bind("triggerEvent", script.triggerEvent) else spec.bindNull("triggerEvent", String::class.java)
                spec = if (script.invocableName != null) spec.bind("invocableName", script.invocableName) else spec.bindNull("invocableName", String::class.java)
                spec = if (script.scheduleCron != null) spec.bind("scheduleCron", script.scheduleCron) else spec.bindNull("scheduleCron", String::class.java)

                spec = spec.bind("source", script.source)
                    .bind("isActive", script.isActive)

                spec = if (script.compiledAt != null) spec.bind("compiledAt", script.compiledAt) else spec.bindNull("compiledAt", Instant::class.java)

                spec.bind("compileErrors", script.compileErrorsJson)
                    .bind("timeoutSeconds", script.timeoutSeconds)
                    .bind("generatedByAi", script.generatedByAi)
                    .bind("createdAt", script.createdAt)
                    .bind("updatedAt", script.updatedAt)
                    .then()
                    .thenReturn(script)
            })
    }

    private fun activateTenant(tenantId: UUID): Mono<Void> {
        return client.sql("SELECT set_config('app.current_tenant', :tenantId, false)")
            .bind("tenantId", tenantId.toString())
            .fetch()
            .rowsUpdated()
            .then()
    }
}
