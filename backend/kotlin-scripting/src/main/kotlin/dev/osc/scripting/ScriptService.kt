package dev.osc.scripting

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.time.Instant

@Service
class ScriptService(
    private val repository: ScriptRepository,
    private val compilerService: KotlinScriptCompilerService,
    private val objectMapper: ObjectMapper
) {

    fun save(script: Script): Mono<Script> {
        return Mono.fromCallable {
            // NNG-004 exception: running blocking compile on boundedElastic
            val compilationResult = compilerService.compile(script.tenantId, script.id, script.source)
            val errorsJson = objectMapper.writeValueAsString(compilationResult.errors)

            // NNG-023: is_active cannot transition to true if there are compile errors
            if (script.isActive && compilationResult.errors.isNotEmpty()) {
                throw IllegalStateException("Cannot activate script with compile errors (NNG-023). Errors: ${compilationResult.errors}")
            }

            script.copy(
                compiledAt = Instant.now(),
                compileErrorsJson = errorsJson,
                // If there are compile errors, override isActive to false just in case (defense-in-depth)
                isActive = if (compilationResult.errors.isNotEmpty()) false else script.isActive,
                updatedAt = Instant.now()
            )
        }
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap { compiledScript ->
            repository.save(compiledScript)
        }
    }
}
