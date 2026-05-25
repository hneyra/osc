package dev.osc.api;

import dev.osc.api.ratelimit.RateLimitConfig;
import dev.osc.api.ratelimit.RateLimitFilter;
import dev.osc.api.ratelimit.TenantRateLimiter;
import dev.osc.metadata.TenantContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RateLimitFilter")
class RateLimitFilterTest {

    private RateLimitFilter buildFilter(int requestsPerMinute) {
        RateLimitConfig config = new RateLimitConfig();
        config.setRequestsPerMinute(requestsPerMinute);
        TenantRateLimiter limiter = new TenantRateLimiter(config);
        return new RateLimitFilter(limiter);
    }

    @Test
    @DisplayName("request within limit passes through with 200")
    void withinLimitPassesThrough() {
        String tenantId = UUID.randomUUID().toString();
        var filter = buildFilter(10);
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/records").build());

        StepVerifier.create(
                filter.filter(exchange, ex -> Mono.empty())
                        .contextWrite(ctx -> ctx.put(TenantContext.TENANT_ID_KEY, tenantId))
        ).verifyComplete();

        // Default status is null (200 OK territory), not 429
        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("request exceeding limit returns 429 with Retry-After header")
    void exceedingLimitReturns429() {
        String tenantId = UUID.randomUUID().toString();
        var filter = buildFilter(2); // limit of 2

        // Send 3 requests — the third must be rejected
        for (int i = 0; i < 2; i++) {
            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/test").build());
            StepVerifier.create(
                    filter.filter(exchange, ex -> Mono.empty())
                            .contextWrite(ctx -> ctx.put(TenantContext.TENANT_ID_KEY, tenantId))
            ).verifyComplete();
        }

        var blockedExchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/test").build());
        StepVerifier.create(
                filter.filter(blockedExchange, ex -> Mono.empty())
                        .contextWrite(ctx -> ctx.put(TenantContext.TENANT_ID_KEY, tenantId))
        ).verifyComplete();

        assertThat(blockedExchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(blockedExchange.getResponse().getHeaders().getFirst("Retry-After"))
                .isEqualTo("60");
    }

    @Test
    @DisplayName("different tenants have independent rate limit counters")
    void differentTenantsAreIsolated() {
        var filter = buildFilter(1);
        String tenant1 = UUID.randomUUID().toString();
        String tenant2 = UUID.randomUUID().toString();

        // Exhaust tenant1 limit
        var ex1 = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build());
        StepVerifier.create(
                filter.filter(ex1, ex -> Mono.empty())
                        .contextWrite(ctx -> ctx.put(TenantContext.TENANT_ID_KEY, tenant1))
        ).verifyComplete();

        var ex1Blocked = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build());
        StepVerifier.create(
                filter.filter(ex1Blocked, ex -> Mono.empty())
                        .contextWrite(ctx -> ctx.put(TenantContext.TENANT_ID_KEY, tenant1))
        ).verifyComplete();

        assertThat(ex1Blocked.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // tenant2 should still pass
        var ex2 = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build());
        StepVerifier.create(
                filter.filter(ex2, ex -> Mono.empty())
                        .contextWrite(ctx -> ctx.put(TenantContext.TENANT_ID_KEY, tenant2))
        ).verifyComplete();

        assertThat(ex2.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("no tenant in context passes through (rate limiter is lenient)")
    void noTenantPassesThrough() {
        var filter = buildFilter(5);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/health").build());

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }
}
