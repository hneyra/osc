package dev.osc.scripting.api

import dev.osc.security.UserContext
import java.time.Instant
import java.util.UUID

interface ExecutionContext {
    val tenantId: UUID
    val currentUser: UserContext
    val trigger: TriggerContext?

    fun <T> records(objectApiName: String): RecordOperations<T>
    fun log(level: LogLevel, message: String)
    fun now(): Instant
    fun checkGuards()
}
