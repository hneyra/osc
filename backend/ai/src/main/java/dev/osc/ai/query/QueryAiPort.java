package dev.osc.ai.query;

import reactor.core.publisher.Mono;

/** Port: AI call that converts a natural-language question to a query DSL string. */
public interface QueryAiPort {
    Mono<String> suggest(String question);
}
