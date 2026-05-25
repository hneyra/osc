package dev.osc.api.ratelimit;

import dev.osc.metadata.TenantContext;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Enforces per-tenant rate limits.
 * Returns 429 Too Many Requests with a Retry-After: 60 header when the limit is exceeded.
 * Runs at @Order(3) — after TenantContextFilter(1) and UserContextFilter(2).
 */
@Component
@Order(3)
public class RateLimitFilter implements WebFilter {

    private final TenantRateLimiter rateLimiter;

    public RateLimitFilter(TenantRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return Mono.deferContextual(ctx -> {
            String tenantId = ctx.getOrDefault(TenantContext.TENANT_ID_KEY, null);

            if (tenantId == null) {
                return chain.filter(exchange);
            }

            if (!rateLimiter.tryAcquire(tenantId)) {
                exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                exchange.getResponse().getHeaders().set("Retry-After", "60");
                return exchange.getResponse().setComplete();
            }

            return chain.filter(exchange);
        });
    }
}
