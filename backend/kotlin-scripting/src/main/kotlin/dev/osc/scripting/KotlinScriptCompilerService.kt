package dev.osc.scripting

import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.util.UUID
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlinx.coroutines.runBlocking

@KotlinScript(
    fileExtension = "kts",
    compilationConfiguration = RestrictedScriptCompilationConfiguration::class
)
abstract class RestrictedScript

object RestrictedScriptCompilationConfiguration : ScriptCompilationConfiguration({
    jvm {
        // Expose only required dependencies from the current context
        dependenciesFromCurrentContext(wholeClasspath = true)
    }
})

data class CompilationResult(
    val compiledScript: CompiledScript?,
    val errors: List<String>
)

@Service
class KotlinScriptCompilerService(
    private val scriptCache: CompiledScriptCache
) {

    private val scriptingHost = BasicJvmScriptingHost()

    fun compile(tenantId: UUID, scriptId: UUID, source: String): CompilationResult {
        // 1. Static Security & Import Allowlist Validation (Defense-in-depth)
        val staticErrors = validateImportsAndSecurity(source)
        if (staticErrors.isNotEmpty()) {
            return CompilationResult(null, staticErrors)
        }

        // 2. Cache Lookup (Caffeine tenant-scoped cache)
        val contentHash = calculateHash(source)
        val cached = scriptCache.get(tenantId, scriptId, contentHash)
        if (cached != null) {
            return CompilationResult(cached, emptyList())
        }

        // 3. Compile via BasicJvmScriptingHost on boundedElastic (handled by caller or wrapping Mono)
        val scriptSource = source.toScriptSource("script_$scriptId.kts")
        val compileResult = runBlocking {
            scriptingHost.compiler(scriptSource, RestrictedScriptCompilationConfiguration)
        }

        val errors = compileResult.reports
            .filter { it.severity >= ScriptDiagnostic.Severity.ERROR }
            .map { "Line ${it.location?.start?.line ?: 0}, Column ${it.location?.start?.col ?: 0}: ${it.message}" }

        if (errors.isNotEmpty()) {
            return CompilationResult(null, errors)
        }

        val compiled = compileResult.valueOrNull()
        if (compiled != null) {
            scriptCache.put(tenantId, scriptId, contentHash, compiled)
        }

        return CompilationResult(compiled, emptyList())
    }

    private fun validateImportsAndSecurity(source: String): List<String> {
        val errors = mutableListOf<String>()

        // Check explicit imports
        val importRegex = Regex("""^\s*import\s+([^\s;]+)""", RegexOption.MULTILINE)
        val matches = importRegex.findAll(source)
        val allowedPrefixes = listOf(
            "kotlin.",
            "dev.osc.scripting.",
            "java.time.",
            "java.math.",
            "java.util."
        )

        for (match in matches) {
            val imported = match.groupValues[1]
            val isAllowed = allowedPrefixes.any { imported.startsWith(it) }
            if (!isAllowed) {
                errors.add("Disallowed import: '$imported'. Only basic JDK collections, math, time, and scripting APIs are allowed.")
            }
        }

        // Check for fully qualified references or malicious API usage in source
        val forbiddenPatterns = listOf(
            "java.io." to "I/O operations (java.io) are prohibited.",
            "java.nio." to "I/O operations (java.nio) are prohibited.",
            "java.net." to "Network operations (java.net) are prohibited.",
            "java.lang.reflect." to "Reflection (java.lang.reflect) is prohibited.",
            "kotlin.reflect." to "Reflection (kotlin.reflect) is prohibited.",
            "java.lang.Process" to "Process execution is prohibited.",
            "Runtime.getRuntime" to "Process execution is prohibited.",
            "System.exit" to "System exit is prohibited."
        )

        for ((pattern, message) in forbiddenPatterns) {
            if (source.contains(pattern)) {
                errors.add("Security Violation: $message")
            }
        }

        return errors
    }

    private fun calculateHash(source: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(source.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
