package dev.osc.api;

import dev.osc.api.observability.CorrelationIdFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CorrelationIdFilter")
class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    @DisplayName("generates correlationId when header is absent")
    void generatesCorrelationIdWhenAbsent() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/test").build());

        AtomicReference<String> captured = new AtomicReference<>();
        var chain = (org.springframework.web.server.WebFilterChain) ex ->
                Mono.deferContextual(ctx -> {
                    captured.set(ctx.getOrDefault(CorrelationIdFilter.CORRELATION_ID_KEY, null));
                    return Mono.empty();
                });

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(captured.get())
                .isNotNull()
                .isNotBlank()
                .matches("[0-9a-fA-F-]{36}"); // UUID format
    }

    @Test
    @DisplayName("propagates correlationId from incoming header")
    void propagatesExistingCorrelationId() {
        String existingId = "test-correlation-id-12345";
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/test")
                        .header(CorrelationIdFilter.CORRELATION_ID_HEADER, existingId)
                        .build());

        AtomicReference<String> captured = new AtomicReference<>();
        var chain = (org.springframework.web.server.WebFilterChain) ex ->
                Mono.deferContextual(ctx -> {
                    captured.set(ctx.getOrDefault(CorrelationIdFilter.CORRELATION_ID_KEY, null));
                    return Mono.empty();
                });

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(captured.get()).isEqualTo(existingId);
    }

    @Test
    @DisplayName("propagates correlationId in response header")
    void propagatesCorrelationIdToResponseHeader() {
        String existingId = "resp-correlation-id-999";
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/test")
                        .header(CorrelationIdFilter.CORRELATION_ID_HEADER, existingId)
                        .build());

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                .verifyComplete();

        assertThat(exchange.getResponse().getHeaders().getFirst(CorrelationIdFilter.CORRELATION_ID_HEADER))
                .isEqualTo(existingId);
    }

    @Test
    @DisplayName("generates a UUID-format correlationId when blank header is sent")
    void blankHeaderGeneratesNewId() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/test")
                        .header(CorrelationIdFilter.CORRELATION_ID_HEADER, "   ")
                        .build());

        AtomicReference<String> captured = new AtomicReference<>();
        var chain = (org.springframework.web.server.WebFilterChain) ex ->
                Mono.deferContextual(ctx -> {
                    captured.set(ctx.getOrDefault(CorrelationIdFilter.CORRELATION_ID_KEY, null));
                    return Mono.empty();
                });

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(captured.get())
                .isNotNull()
                .matches("[0-9a-fA-F-]{36}");
    }
}
