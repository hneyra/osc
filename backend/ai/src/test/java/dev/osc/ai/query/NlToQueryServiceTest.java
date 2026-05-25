package dev.osc.ai.query;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TDD — written before NlToQueryService exists.
 * QueryAiPort abstracts the AI call; PermissionFilterPort enforces FLS.
 */
class NlToQueryServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-0000-0000-0000-000000000000");
    private static final UUID USER_ID   = UUID.fromString("22222222-0000-0000-0000-000000000000");

    private QueryAiPort aiPort;
    private QueryPermissionPort permissionPort;
    private NlToQueryService service;

    @BeforeEach
    void setUp() {
        aiPort         = mock(QueryAiPort.class);
        permissionPort = mock(QueryPermissionPort.class);
        service        = new NlToQueryService(aiPort, permissionPort);
    }

    @Test
    @DisplayName("suggest returns QuerySuggestion when AI and permission check succeed")
    void suggest_success() {
        String aiDsl = "SELECT name, industry FROM Account WHERE industry = 'Tech'";
        when(aiPort.suggest(anyString())).thenReturn(Mono.just(aiDsl));
        when(permissionPort.filterFields(eq(TENANT_ID), eq(USER_ID), any()))
                .thenAnswer(inv -> Mono.just(inv.getArgument(2)));

        StepVerifier.create(service.suggest("Show me tech accounts", TENANT_ID, USER_ID))
                .expectNextMatches(s -> s.queryDsl().contains("Account"))
                .verifyComplete();
    }

    @Test
    @DisplayName("suggest returns error when AI port fails")
    void suggest_aiError() {
        when(aiPort.suggest(anyString()))
                .thenReturn(Mono.error(new RuntimeException("model timeout")));

        StepVerifier.create(service.suggest("anything", TENANT_ID, USER_ID))
                .expectError(RuntimeException.class)
                .verify();
    }

    @Test
    @DisplayName("suggest rejects blank input")
    void suggest_blankInput() {
        StepVerifier.create(service.suggest("", TENANT_ID, USER_ID))
                .expectErrorMatches(e -> e instanceof IllegalArgumentException)
                .verify();

        verifyNoInteractions(aiPort);
    }

    @Test
    @DisplayName("permission filter is applied to AI-generated query")
    void suggest_permissionFilterApplied() {
        String aiDsl = "SELECT name, secret FROM Account";
        String filteredDsl = "SELECT name FROM Account";
        when(aiPort.suggest(anyString())).thenReturn(Mono.just(aiDsl));
        when(permissionPort.filterFields(eq(TENANT_ID), eq(USER_ID), any()))
                .thenReturn(Mono.just(new QuerySuggestion(filteredDsl)));

        StepVerifier.create(service.suggest("show account names and secrets", TENANT_ID, USER_ID))
                .expectNextMatches(s -> s.queryDsl().equals(filteredDsl))
                .verifyComplete();

        verify(permissionPort).filterFields(eq(TENANT_ID), eq(USER_ID), any());
    }
}
