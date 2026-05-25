package dev.osc.api.tenant;

import dev.osc.metadata.TenantContext;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Extracts tenant_id from the incoming request and places it in the Reactor Context.
 * Phase 1 strategy: header X-Tenant-ID (Phase 4 will replace with JWT claim).
 *
 * NEVER reads tenant_id from request body or path parameters.
 * Missing or malformed tenant → 401 Unauthorized (fail closed).
 */
@Component
@Order(1)
public class TenantContextFilter implements WebFilter {

    public static final String TENANT_HEADER = "X-Tenant-ID";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String rawTenantId = exchange.getRequest().getHeaders().getFirst(TENANT_HEADER);

        if (rawTenantId == null || rawTenantId.isBlank()) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        try {
            String tenantId = UUID.fromString(rawTenantId.trim()).toString();
            return chain.filter(exchange)
                    .contextWrite(ctx -> ctx.put(TenantContext.TENANT_ID_KEY, tenantId));
        } catch (IllegalArgumentException e) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
    }
}
