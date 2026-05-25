package dev.osc.query;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Secondary port — executes a TranslatedQuery against the database via R2DBC.
 * Returns typed rows as Map<fieldApiName, value>.
 * Supports backpressure streaming via Flux.
 */
public interface QueryExecutor {

    Flux<Map<String, Object>> execute(TranslatedQuery query);

    Mono<Long> count(TranslatedQuery query);
}
