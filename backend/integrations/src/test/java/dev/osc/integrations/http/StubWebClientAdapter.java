package dev.osc.integrations.http;

import reactor.core.publisher.Mono;

import java.util.Map;

class StubWebClientAdapter implements WebClientAdapter {
    private final int statusCode;

    StubWebClientAdapter(int statusCode) {
        this.statusCode = statusCode;
    }

    @Override
    public Mono<Integer> post(String url, String body, Map<String, String> headers) {
        return Mono.just(statusCode);
    }
}
