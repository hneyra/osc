package dev.osc.scripting

import dev.osc.automation.engine.UserCodeExecutor
import dev.osc.automation.engine.UserCodeResult
import dev.osc.metadata.MetadataEngine
import dev.osc.persistence.DynamicPersistenceService
import dev.osc.query.QueryExecutor
import dev.osc.query.QueryParser
import dev.osc.query.QueryTranslator
import dev.osc.scripting.api.TriggerContext
import dev.osc.security.FlsFilter
import dev.osc.security.PermissionChecker
import dev.osc.security.UserContext
import org.springframework.stereotype.Component
import java.util.UUID
import kotlin.script.experimental.api.valueOrNull

@Component("kotlinScriptExecutor")
class KotlinScriptExecutor(
    private val compilerService: KotlinScriptCompilerService,
    private val sandbox: ScriptSandbox,
    private val auditor: ScriptExecutionAuditor,
    private val persistenceService: DynamicPersistenceService,
    private val queryParser: QueryParser,
    private val queryTranslator: QueryTranslator,
    private val queryExecutor: QueryExecutor,
    private val permissionChecker: PermissionChecker,
    private val flsFilter: FlsFilter,
    private val metadataEngine: MetadataEngine
) : UserCodeExecutor {

    override fun execute(code: String, context: Map<String, Any>): UserCodeResult {
        val tenantId = context["tenantId"] as? UUID ?: return UserCodeResult.failure("Missing tenantId in execution context")
        val currentUser = context["currentUser"] as? UserContext ?: return UserCodeResult.failure("Missing currentUser in execution context")
        
        val objectApiName = context["objectApiName"] as? String
        val triggerEventStr = context["triggerEvent"] as? String

        val triggerContext = (context["trigger"] as? TriggerContext) ?: if (triggerEventStr != null && objectApiName != null) {
            val event = try {
                dev.osc.scripting.api.TriggerEvent.valueOf(triggerEventStr)
            } catch (e: Exception) {
                null
            }
            if (event != null) {
                val newMaps = context["newRecords"] as? List<Map<String, Any?>> ?: emptyList()
                val oldMaps = context["oldRecords"] as? List<Map<String, Any?>> ?: emptyList()
                
                val newRecords = newMaps.map { dev.osc.scripting.api.DynamicRecord(objectApiName, it.toMutableMap()) }
                val oldRecords = oldMaps.map { dev.osc.scripting.api.DynamicRecord(objectApiName, it.toMutableMap()) }
                
                TriggerContextImpl(event, newRecords, oldRecords)
            } else null
        } else null

        val scriptId = context["scriptId"] as? UUID ?: UUID.randomUUID()
        val triggerEventName = context["triggerEvent"] as? String ?: "API"
        val timeoutSeconds = (context["timeoutSeconds"] as? Number)?.toInt() ?: 5

        val compilationResult = compilerService.compile(tenantId, scriptId, code)
        if (compilationResult.compiledScript == null) {
            return UserCodeResult.failure("Script compilation failed: ${compilationResult.errors.joinToString(", ")}")
        }

        val execContext = ExecutionContextImpl(
            tenantId = tenantId,
            currentUser = currentUser,
            trigger = triggerContext,
            persistenceService = persistenceService,
            queryParser = queryParser,
            queryTranslator = queryTranslator,
            queryExecutor = queryExecutor,
            permissionChecker = permissionChecker,
            flsFilter = flsFilter,
            metadataEngine = metadataEngine
        )

        val startTime = System.currentTimeMillis()
        return try {
            val evalResult = sandbox.execute(compilationResult.compiledScript, execContext, timeoutSeconds)
            val durationMs = System.currentTimeMillis() - startTime

            val errors = evalResult.reports.filter { it.severity >= kotlin.script.experimental.api.ScriptDiagnostic.Severity.ERROR }
            if (errors.isNotEmpty()) {
                val errorMsg = errors.joinToString(", ") { it.message }
                auditor.audit(tenantId, scriptId, triggerEventName, durationMs.toInt(), "FAILED", execContext.getLogOutput() + "\nError: " + errorMsg)
                    .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                    .subscribe()
                UserCodeResult.failure("Script execution failed: $errorMsg")
            } else if (triggerContext != null && triggerContext.errors.isNotEmpty()) {
                val scriptValidationErrors = triggerContext.errors.joinToString(", ")
                auditor.audit(tenantId, scriptId, triggerEventName, durationMs.toInt(), "FAILED", execContext.getLogOutput() + "\nValidation Errors: " + scriptValidationErrors)
                    .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                    .subscribe()
                UserCodeResult.failure("Validation failed: $scriptValidationErrors")
            } else {
                auditor.audit(tenantId, scriptId, triggerEventName, durationMs.toInt(), "SUCCESS", execContext.getLogOutput())
                    .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                    .subscribe()
                val outputMap = mutableMapOf<String, Any?>()
                if (triggerContext != null) {
                    outputMap["newRecords"] = triggerContext.newRecords.map { it.fields }
                }
                outputMap["returnValue"] = evalResult.valueOrNull()?.returnValue
                UserCodeResult.success(outputMap)
            }
        } catch (e: Exception) {
            val durationMs = System.currentTimeMillis() - startTime
            val outcome = if (e is java.util.concurrent.TimeoutException) "TIMEOUT" else "FAILED"
            auditor.audit(tenantId, scriptId, triggerEventName, durationMs.toInt(), outcome, execContext.getLogOutput() + "\nException: " + e.message)
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .subscribe()
            UserCodeResult.failure("Script execution exception: ${e.message}")
        }
    }
}
