package dev.osc.ai.metadata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * TDD — written before NlToMetadataService exists.
 * MetadataAiPort is a port that abstracts the AI call; mocked here.
 */
class NlToMetadataServiceTest {

    private MetadataAiPort aiPort;
    private NlToMetadataService service;

    @BeforeEach
    void setUp() {
        aiPort  = mock(MetadataAiPort.class);
        service = new NlToMetadataService(aiPort);
    }

    @Test
    @DisplayName("suggest returns parsed MetadataSuggestion when AI returns valid JSON")
    void suggest_validResponse() {
        String aiJson = """
                {
                  "objectApiName": "Project",
                  "label": "Project",
                  "labelPlural": "Projects",
                  "fields": [
                    {"apiName": "name", "label": "Name", "fieldType": "TEXT"},
                    {"apiName": "dueDate", "label": "Due Date", "fieldType": "DATE"}
                  ]
                }
                """;
        when(aiPort.suggest(anyString())).thenReturn(Mono.just(aiJson));

        StepVerifier.create(service.suggest("I need to track projects with a name and due date"))
                .expectNextMatches(s ->
                        "Project".equals(s.objectApiName()) &&
                        s.fields().size() == 2 &&
                        s.fields().get(0).apiName().equals("name"))
                .verifyComplete();
    }

    @Test
    @DisplayName("suggest returns error when AI returns malformed JSON")
    void suggest_malformedJson() {
        when(aiPort.suggest(anyString())).thenReturn(Mono.just("not-valid-json"));

        StepVerifier.create(service.suggest("some description"))
                .expectErrorMatches(e -> e instanceof MetadataSuggestionParseException)
                .verify();
    }

    @Test
    @DisplayName("suggest propagates error from AI port")
    void suggest_aiPortError() {
        when(aiPort.suggest(anyString()))
                .thenReturn(Mono.error(new RuntimeException("AI unavailable")));

        StepVerifier.create(service.suggest("anything"))
                .expectError(RuntimeException.class)
                .verify();
    }

    @Test
    @DisplayName("suggest rejects metadata that violates the ObjectDefinition contract")
    void suggest_contractViolationRejected() {
        String aiJson = """
                {
                  "objectApiName": "1Invalid",
                  "label": "X",
                  "labelPlural": "Xs",
                  "fields": [
                    {"apiName": "name", "label": "Name", "fieldType": "TEXT"}
                  ]
                }
                """;
        when(aiPort.suggest(anyString())).thenReturn(Mono.just(aiJson));

        StepVerifier.create(service.suggest("an object whose api name is invalid"))
                .expectErrorMatches(e -> e instanceof MetadataSuggestionParseException)
                .verify();
    }

    @Test
    @DisplayName("suggest rejects blank description")
    void suggest_blankDescription() {
        StepVerifier.create(service.suggest("  "))
                .expectErrorMatches(e -> e instanceof IllegalArgumentException)
                .verify();

        verifyNoInteractions(aiPort);
    }
}
