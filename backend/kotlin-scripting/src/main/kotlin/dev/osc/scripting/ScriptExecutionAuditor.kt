package dev.osc.scripting

import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import java.util.UUID

@Component
class ScriptExecutionAuditor(private val logRepository: ScriptExecutionLogRepository) {

    fun audit(
        tenantId: UUID,
        scriptId: UUID,
        triggerContext: String?,
        durationMs: Int,
        outcome: String,
        logOutput: String?
    ): Mono<ScriptExecutionLog> {
        val log = ScriptExecutionLog(
            tenantId = tenantId,
            scriptId = scriptId,
            triggerContext = triggerContext,
            durationMs = durationMs,
            outcome = outcome,
            logOutput = logOutput
        )
        return logRepository.save(log)
    }
}
