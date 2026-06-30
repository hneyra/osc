package dev.osc.ai.script;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.osc.scripting.CompilationResult;
import dev.osc.scripting.KotlinScriptCompilerService;
import dev.osc.scripting.Script;
import dev.osc.scripting.ScriptRepository;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.UUID;

/**
 * Service for translating natural language descriptions into Kotlin script proposals.
 * Complies with NNG-017 and NNG-025 by always ensuring AI-generated scripts are saved
 * with generated_by_ai = true and is_active = false.
 */
public class NlToScriptService {

    private final ScriptAiPort aiPort;
    private final ScriptRepository scriptRepository;
    private final KotlinScriptCompilerService compilerService;
    private final ObjectMapper objectMapper;

    public NlToScriptService(
            ScriptAiPort aiPort,
            ScriptRepository scriptRepository,
            KotlinScriptCompilerService compilerService,
            ObjectMapper objectMapper
    ) {
        this.aiPort = aiPort;
        this.scriptRepository = scriptRepository;
        this.compilerService = compilerService;
        this.objectMapper = objectMapper;
    }

    /**
     * Proposes and persists a Kotlin script from a natural language description.
     */
    public Mono<Script> propose(
            UUID tenantId,
            UUID objectId,
            String objectApiName,
            String description,
            String kind,
            String triggerEvent,
            String invocableName,
            String scheduleCron
    ) {
        if (description == null || description.isBlank()) {
            return Mono.error(new IllegalArgumentException("description must not be blank"));
        }
        if (tenantId == null) {
            return Mono.error(new IllegalArgumentException("tenantId must not be null"));
        }
        if (objectId == null) {
            return Mono.error(new IllegalArgumentException("objectId must not be null"));
        }
        if (kind == null || kind.isBlank()) {
            return Mono.error(new IllegalArgumentException("kind must not be blank"));
        }

        UUID scriptId = UUID.randomUUID();

        return aiPort.propose(description, objectApiName, kind, triggerEvent)
                .flatMap(source -> Mono.fromCallable(() -> {
                    // Run compilation check only
                    CompilationResult result = compilerService.compile(tenantId, scriptId, source);
                    String errorsJson = objectMapper.writeValueAsString(result.getErrors());

                    return new Script(
                            scriptId,
                            tenantId,
                            objectId,
                            kind,
                            triggerEvent,
                            invocableName,
                            scheduleCron,
                            source,
                            false, // NNG-025: generated script always persists with is_active = false
                            Instant.now(),
                            errorsJson,
                            5, // default timeout_seconds
                            true, // NNG-025: generated_by_ai = true
                            Instant.now(),
                            Instant.now()
                    );
                })
                .subscribeOn(Schedulers.boundedElastic()))
                .flatMap(scriptRepository::save);
    }
}
