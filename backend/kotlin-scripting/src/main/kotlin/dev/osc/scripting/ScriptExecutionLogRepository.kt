package dev.osc.scripting

import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import reactor.core.publisher.Mono
import java.util.UUID

@Repository
class ScriptExecutionLogRepository(private val client: DatabaseClient) {

    fun save(log: ScriptExecutionLog): Mono<ScriptExecutionLog> {
        return activateTenant(log.tenantId)
            .then(Mono.defer {
                var spec = client.sql("""
                    INSERT INTO script_execution_log (
                        id, tenant_id, script_id, trigger_context, duration_ms, outcome, log_output, created_at
                    ) VALUES (
                        :id, :tenantId, :scriptId, :triggerContext, :durationMs, :outcome, :logOutput, :createdAt
                    )
                    """)
                    .bind("id", log.id)
                    .bind("tenantId", log.tenantId)
                    .bind("scriptId", log.scriptId)
                    .bind("durationMs", log.durationMs)
                    .bind("outcome", log.outcome)
                    .bind("createdAt", log.createdAt)

                spec = if (log.triggerContext != null) spec.bind("triggerContext", log.triggerContext) else spec.bindNull("triggerContext", String::class.java)
                spec = if (log.logOutput != null) spec.bind("logOutput", log.logOutput) else spec.bindNull("logOutput", String::class.java)

                spec.then().thenReturn(log)
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
