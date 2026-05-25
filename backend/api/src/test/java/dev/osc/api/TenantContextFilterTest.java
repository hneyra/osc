package dev.osc.api;

import dev.osc.api.tenant.TenantContextFilter;
import dev.osc.metadata.TenantContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.reactive.function.server.MockServerRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TenantContextFilter")
class TenantContextFilterTest {

    private final TenantContextFilter filter = new TenantContextFilter();

    @Test
    @DisplayName("valid UUID header → tenant placed in Reactor Context")
    void validHeader_putsTenantInContext() {
        String tenantId = UUID.randomUUID().toString();
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/test")
                        .header(TenantContextFilter.TENANT_HEADER, tenantId)
                        .build());

        AtomicReference<String> capturedTenant = new AtomicReference<>();
        var chain = (org.springframework.web.server.WebFilterChain) ex ->
                Mono.deferContextual(ctx -> {
                    capturedTenant.set(ctx.getOrDefault(TenantContext.TENANT_ID_KEY, null));
                    return Mono.empty();
                });

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertEquals(tenantId, capturedTenant.get());
    }

    @Test
    @DisplayName("missing header → 401 Unauthorized")
    void missingHeader_returns401() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/test").build());

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                .verifyComplete();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("invalid UUID in header → 401 Unauthorized")
    void invalidUuid_returns401() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/test")
                        .header(TenantContextFilter.TENANT_HEADER, "not-a-uuid")
                        .build());

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                .verifyComplete();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("blank header → 401 Unauthorized")
    void blankHeader_returns401() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/test")
                        .header(TenantContextFilter.TENANT_HEADER, "   ")
                        .build());

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                .verifyComplete();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }
}
