package dev.osc.integrations.http;

import reactor.core.publisher.Mono;

import java.util.Map;

/** Seam that wraps the real WebClient for testability. */
public interface WebClientAdapter {
    Mono<Integer> post(String url, String body, Map<String, String> headers);
}
