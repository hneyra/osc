package dev.osc.integrations.http;

import reactor.core.publisher.Mono;

import java.util.Map;

public interface OutboundHttpClient {
    Mono<Integer> post(String url, String body, Map<String, String> headers);
}
