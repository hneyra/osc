package dev.osc.scripting

import java.time.Instant
import java.util.UUID

/**
 * Metadata record representing a Kotlin script (ADR-005).
 */
data class Script(
    val id: UUID = UUID.randomUUID(),
    val tenantId: UUID,
    val objectId: UUID,
    val kind: String,
    val triggerEvent: String? = null,
    val invocableName: String? = null,
    val scheduleCron: String? = null,
    val source: String,
    val isActive: Boolean = false,
    val compiledAt: Instant? = null,
    val compileErrorsJson: String = "[]",
    val timeoutSeconds: Int = 5,
    val generatedByAi: Boolean = false,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)
