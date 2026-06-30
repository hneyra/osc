package dev.osc.scripting

import dev.osc.metadata.MetadataEngine
import dev.osc.persistence.DynamicPersistenceService
import dev.osc.query.QueryExecutor
import dev.osc.query.QueryParser
import dev.osc.query.QueryTranslator
import dev.osc.scripting.api.ExecutionContext
import dev.osc.scripting.api.LogLevel
import dev.osc.scripting.api.RecordOperations
import dev.osc.scripting.api.TriggerContext
import dev.osc.security.FlsFilter
import dev.osc.security.PermissionChecker
import dev.osc.security.UserContext
import java.time.Instant
import java.util.UUID

class ExecutionContextImpl(
    override val tenantId: UUID,
    override val currentUser: UserContext,
    override val trigger: TriggerContext? = null,
    private val persistenceService: DynamicPersistenceService,
    private val queryParser: QueryParser,
    private val queryTranslator: QueryTranslator,
    private val queryExecutor: QueryExecutor,
    private val permissionChecker: PermissionChecker,
    private val flsFilter: FlsFilter,
    private val metadataEngine: MetadataEngine
) : ExecutionContext {

    private val logs = mutableListOf<String>()

    override fun <T> records(objectApiName: String): RecordOperations<T> {
        checkGuards()
        return RecordOperationsImpl(
            objectApiName = objectApiName,
            ctx = this,
            persistenceService = persistenceService,
            queryParser = queryParser,
            queryTranslator = queryTranslator,
            queryExecutor = queryExecutor,
            permissionChecker = permissionChecker,
            flsFilter = flsFilter,
            metadataEngine = metadataEngine
        )
    }

    override fun log(level: LogLevel, message: String) {
        checkGuards()
        val timestamp = Instant.now()
        logs.add("[$timestamp] [$level] $message")
    }

    // Overload for string representation of LogLevel for backward compatibility or simple callers
    fun log(level: String, message: String) {
        checkGuards()
        val lvl = try {
            LogLevel.valueOf(level.uppercase())
        } catch (e: Exception) {
            LogLevel.INFO
        }
        log(lvl, message)
    }

    override fun now(): Instant {
        checkGuards()
        return Instant.now()
    }

    override fun checkGuards() {
        val stackTrace = Thread.currentThread().stackTrace
        // Restrict recursion depth to 200 to prevent stack overflow attacks
        if (stackTrace.size > 200) {
            throw SecurityException("Max recursion depth exceeded (limit 200)")
        }
    }

    fun getLogOutput(): String {
        return logs.joinToString("\n")
    }
}
