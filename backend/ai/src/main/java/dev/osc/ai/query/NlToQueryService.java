package dev.osc.ai.query;

import reactor.core.publisher.Mono;

import java.util.UUID;

public class NlToQueryService {

    private final QueryAiPort aiPort;
    private final QueryPermissionPort permissionPort;

    public NlToQueryService(QueryAiPort aiPort, QueryPermissionPort permissionPort) {
        this.aiPort         = aiPort;
        this.permissionPort = permissionPort;
    }

    public Mono<QuerySuggestion> suggest(String question, UUID tenantId, UUID userId) {
        if (question == null || question.isBlank()) {
            return Mono.error(new IllegalArgumentException("question must not be blank"));
        }
        return aiPort.suggest(question)
                .map(QuerySuggestion::new)
                .flatMap(suggestion -> permissionPort.filterFields(tenantId, userId, suggestion));
    }
}
