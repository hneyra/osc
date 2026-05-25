package dev.osc.ai.query;

import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Port: applies permission enforcement to an AI-generated query suggestion.
 * Strips fields the user is not allowed to read.
 */
public interface QueryPermissionPort {
    Mono<QuerySuggestion> filterFields(UUID tenantId, UUID userId, QuerySuggestion suggestion);
}
