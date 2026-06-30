package dev.osc.scripting

import dev.osc.metadata.MetadataEngine
import dev.osc.persistence.DynamicPersistenceService
import dev.osc.persistence.RecordEntity
import dev.osc.query.QueryExecutor
import dev.osc.query.QueryParser
import dev.osc.query.QueryTranslator
import dev.osc.scripting.api.*
import kotlin.script.experimental.api.*
import dev.osc.security.FlsFilter
import dev.osc.security.PermissionChecker
import dev.osc.security.UserContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import reactor.core.publisher.Mono
import java.time.Instant
import java.util.*
import java.util.concurrent.TimeoutException

class ScriptSandboxTest {

    private lateinit var persistenceService: DynamicPersistenceService
    private lateinit var queryParser: QueryParser
    private lateinit var queryTranslator: QueryTranslator
    private lateinit var queryExecutor: QueryExecutor
    private lateinit var permissionChecker: PermissionChecker
    private lateinit var flsFilter: FlsFilter
    private lateinit var metadataEngine: MetadataEngine
    private lateinit var cache: CompiledScriptCache
    private lateinit var compilerService: KotlinScriptCompilerService
    private lateinit var sandbox: ScriptSandbox
    private lateinit var auditor: ScriptExecutionAuditor
    private lateinit var logRepository: ScriptExecutionLogRepository

    private val tenantId = UUID.randomUUID()
    private val userId = UUID.randomUUID()
    private val currentUser = UserContext(userId, tenantId)
    private val objectApiName = "Account"

    @BeforeEach
    fun setUp() {
        persistenceService = mock(DynamicPersistenceService::class.java)
        queryParser = mock(QueryParser::class.java)
        queryTranslator = mock(QueryTranslator::class.java)
        queryExecutor = mock(QueryExecutor::class.java)
        permissionChecker = mock(PermissionChecker::class.java)
        flsFilter = mock(FlsFilter::class.java)
        metadataEngine = mock(MetadataEngine::class.java)
        cache = CompiledScriptCache()
        compilerService = KotlinScriptCompilerService(cache)
        sandbox = ScriptSandbox()
        logRepository = mock(ScriptExecutionLogRepository::class.java)
        auditor = ScriptExecutionAuditor(logRepository)
    }

    @Test
    fun `test script permission enforcement with fls and rls blocks forbidden fields NNG-024`() {
        // 1. Script source that reads a record
        val scriptSource = """
            val record = ctx.records<dev.osc.scripting.api.DynamicRecord>("Account").findById(java.util.UUID.fromString("${UUID.randomUUID()}"))
            ctx.log(dev.osc.scripting.api.LogLevel.INFO, "Fetched record: " + record)
        """.trimIndent()

        // Compile script
        val compilationResult = compilerService.compile(tenantId, UUID.randomUUID(), scriptSource)
        assertNotNull(compilationResult.compiledScript, "Compilation failed: ${compilationResult.errors}")

        // Mock permission checks: READ is allowed, but FLS restricts fields (only "id" and "name" are allowed, not "annual_revenue__c")
        val recordId = UUID.randomUUID()
        `when`(permissionChecker.canRead(anyKotlin(), anyKotlin(), anyKotlin())).thenReturn(Mono.just(true))
        `when`(permissionChecker.allowedReadFields(anyKotlin(), anyKotlin(), anyKotlin()))
            .thenReturn(Mono.just(setOf("name"))) // FLS allows "name" only

        val rawData = mapOf("name" to "Acme Corp", "annual_revenue__c" to 1000000.0)
        val entity = RecordEntity(recordId, tenantId, UUID.randomUUID(), "Acme Corp", UUID.randomUUID(), rawData, Instant.now(), Instant.now())
        `when`(persistenceService.getRecord(anyKotlin())).thenReturn(Mono.just(entity))

        // FlsFilter mock behaviour
        `when`(flsFilter.apply(anyKotlin(), anyKotlin(), anyKotlin(), anyKotlin())).thenAnswer { invocation ->
            val rec = invocation.getArgument(0) as Map<String, Any>
            Mono.just(rec.filterKeys { it == "id" || it == "name" }) // strip annual_revenue__c
        }

        // Run in sandbox
        val context = ExecutionContextImpl(
            tenantId = tenantId,
            currentUser = currentUser,
            persistenceService = persistenceService,
            queryParser = queryParser,
            queryTranslator = queryTranslator,
            queryExecutor = queryExecutor,
            permissionChecker = permissionChecker,
            flsFilter = flsFilter,
            metadataEngine = metadataEngine
        )

        val evalResult = sandbox.execute(compilationResult.compiledScript!!, context, 5)
        val reportsText = evalResult.reports.joinToString("\n") { "[${it.severity}] ${it.message}" }
        assertTrue(evalResult.reports.none { it.severity >= ScriptDiagnostic.Severity.ERROR }, "Evaluation had errors: $reportsText")

        // Check that annual_revenue__c is stripped
        val logOutput = context.getLogOutput()
        assertTrue(logOutput.contains("Acme Corp"), "Log output does not contain 'Acme Corp'. Full log output:\n$logOutput\nReports:\n$reportsText")
        assertFalse(logOutput.contains("annual_revenue__c"), "Log output should not contain 'annual_revenue__c'. Full log output:\n$logOutput")
    }

    @Test
    fun `test script timeout kills long running execution and throws exception`() {
        val scriptSource = """
            while(true) {
                // Infinite loop
            }
        """.trimIndent()

        val compilationResult = compilerService.compile(tenantId, UUID.randomUUID(), scriptSource)
        assertNotNull(compilationResult.compiledScript, "Compilation failed: ${compilationResult.errors}")

        val context = ExecutionContextImpl(
            tenantId = tenantId,
            currentUser = currentUser,
            persistenceService = persistenceService,
            queryParser = queryParser,
            queryTranslator = queryTranslator,
            queryExecutor = queryExecutor,
            permissionChecker = permissionChecker,
            flsFilter = flsFilter,
            metadataEngine = metadataEngine
        )

        assertThrows(TimeoutException::class.java) {
            sandbox.execute(compilationResult.compiledScript!!, context, 1)
        }
    }

    @Test
    fun `test script execution auditor writes logs successfully`() {
        val logId = UUID.randomUUID()
        val scriptId = UUID.randomUUID()
        val log = ScriptExecutionLog(
            id = logId,
            tenantId = tenantId,
            scriptId = scriptId,
            triggerContext = "BEFORE_INSERT",
            durationMs = 150,
            outcome = "SUCCESS",
            logOutput = "Some test execution log output"
        )

        `when`(logRepository.save(anyKotlin())).thenReturn(Mono.just(log))

        val resultMono = auditor.audit(tenantId, scriptId, "BEFORE_INSERT", 150, "SUCCESS", "Some test execution log output")
        
        var savedLog: ScriptExecutionLog? = null
        resultMono.subscribe { savedLog = it }
        
        assertNotNull(savedLog)
        assertEquals("SUCCESS", savedLog?.outcome)
        assertEquals("Some test execution log output", savedLog?.logOutput)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyKotlin(): T {
        any<T>()
        return null as T
    }
}
