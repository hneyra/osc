package dev.osc.scripting.api

import java.time.Instant
import java.util.UUID

interface ExecutionContext {
    val tenantId: UUID
    fun log(level: String, message: String)
    fun now(): Instant
}
