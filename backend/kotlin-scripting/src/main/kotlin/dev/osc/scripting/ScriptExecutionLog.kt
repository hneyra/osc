package dev.osc.scripting

import java.time.Instant
import java.util.UUID

data class ScriptExecutionLog(
    val id: UUID = UUID.randomUUID(),
    val tenantId: UUID,
    val scriptId: UUID,
    val triggerContext: String? = null,
    val durationMs: Int,
    val outcome: String,
    val logOutput: String? = null,
    val createdAt: Instant = Instant.now()
)
