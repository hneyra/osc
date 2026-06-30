package dev.osc.ai.script;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.osc.scripting.CompilationResult;
import dev.osc.scripting.KotlinScriptCompilerService;
import dev.osc.scripting.Script;
import dev.osc.scripting.ScriptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiScriptProposalServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID OBJECT_ID = UUID.randomUUID();

    private ScriptAiPort aiPort;
    private ScriptRepository scriptRepository;
    private KotlinScriptCompilerService compilerService;
    private ObjectMapper objectMapper;
    private NlToScriptService service;

    @BeforeEach
    void setUp() {
        aiPort = mock(ScriptAiPort.class);
        scriptRepository = mock(ScriptRepository.class);
        compilerService = mock(KotlinScriptCompilerService.class);
        objectMapper = new ObjectMapper();
        service = new NlToScriptService(aiPort, scriptRepository, compilerService, objectMapper);
    }

    @Test
    @DisplayName("NNG-025: Generated script always persists with is_active = false and generated_by_ai = true")
    void propose_setsCorrectFlagsAndPersists() {
        String proposedCode = "ctx.log.info(\"Hello from AI\")";
        
        when(aiPort.propose(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Mono.just(proposedCode));
                
        CompilationResult compilationResult = new CompilationResult(null, Collections.emptyList());
        when(compilerService.compile(eq(TENANT_ID), any(UUID.class), eq(proposedCode)))
                .thenReturn(compilationResult);
                
        when(scriptRepository.save(any(Script.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.propose(
                TENANT_ID,
                OBJECT_ID,
                "Account",
                "Trigger greeting on Account",
                "TRIGGER",
                "BEFORE_INSERT",
                null,
                null
        ))
        .expectNextMatches(script -> {
            assertThat(script.getGeneratedByAi()).isTrue();
            assertThat(script.isActive()).isFalse();
            assertThat(script.getSource()).isEqualTo(proposedCode);
            assertThat(script.getCompileErrorsJson()).isEqualTo("[]");
            return true;
        })
        .verifyComplete();

        ArgumentCaptor<Script> scriptCaptor = ArgumentCaptor.forClass(Script.class);
        verify(scriptRepository).save(scriptCaptor.capture());
        Script savedScript = scriptCaptor.getValue();
        assertThat(savedScript.getGeneratedByAi()).isTrue();
        assertThat(savedScript.isActive()).isFalse();
    }

    @Test
    @DisplayName("A proposal with compile errors is still persisted (visible for fixing) but cannot be activated")
    void propose_withCompileErrors_isStillPersistedButInactive() {
        String badCode = "invalid kotlin code";
        List<String> errors = List.of("Line 1, Column 1: Unresolved reference: invalid");

        when(aiPort.propose(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Mono.just(badCode));

        CompilationResult compilationResult = new CompilationResult(null, errors);
        when(compilerService.compile(eq(TENANT_ID), any(UUID.class), eq(badCode)))
                .thenReturn(compilationResult);

        when(scriptRepository.save(any(Script.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.propose(
                TENANT_ID,
                OBJECT_ID,
                "Account",
                "Broken code generation",
                "TRIGGER",
                "BEFORE_INSERT",
                null,
                null
        ))
        .expectNextMatches(script -> {
            assertThat(script.getGeneratedByAi()).isTrue();
            assertThat(script.isActive()).isFalse();
            assertThat(script.getSource()).isEqualTo(badCode);
            assertThat(script.getCompileErrorsJson()).contains("Unresolved reference");
            return true;
        })
        .verifyComplete();

        ArgumentCaptor<Script> scriptCaptor = ArgumentCaptor.forClass(Script.class);
        verify(scriptRepository).save(scriptCaptor.capture());
        Script savedScript = scriptCaptor.getValue();
        assertThat(savedScript.isActive()).isFalse();
        assertThat(savedScript.getCompileErrorsJson()).contains("Unresolved reference");
    }

    @Test
    @DisplayName("No execution path is reachable from the NlToScriptService - only compile-check")
    void propose_doesNotExecuteScript() {
        String code = "ctx.log.info(\"Do not run\")";

        when(aiPort.propose(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Mono.just(code));

        CompilationResult compilationResult = new CompilationResult(null, Collections.emptyList());
        when(compilerService.compile(eq(TENANT_ID), any(UUID.class), eq(code)))
                .thenReturn(compilationResult);

        when(scriptRepository.save(any(Script.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.propose(
                TENANT_ID,
                OBJECT_ID,
                "Account",
                "Do not execute",
                "TRIGGER",
                "BEFORE_INSERT",
                null,
                null
        ))
        .expectNextCount(1)
        .verifyComplete();

        // Verify only compiler was touched, no runtime executor or sandbox was used
        verify(compilerService, times(1)).compile(eq(TENANT_ID), any(UUID.class), eq(code));
        verifyNoMoreInteractions(compilerService);
    }
}
