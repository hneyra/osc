package dev.osc.api.tenant;

import dev.osc.security.SecurityContext;
import dev.osc.security.UserContext;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Extracts user identity from the incoming request and places it in the Reactor Context.
 * Phase 4 strategy: X-User-ID header (Phase 5+ will replace with JWT claims).
 *
 * Missing or malformed user → 401 Unauthorized (fail closed).
 * Must run after TenantContextFilter to read the tenantId from context.
 */
@Component
@Order(2)
public class UserContextFilter implements WebFilter {

    public static final String USER_ID_HEADER = "X-User-ID";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String rawUserId = exchange.getRequest().getHeaders().getFirst(USER_ID_HEADER);

        if (rawUserId == null || rawUserId.isBlank()) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        try {
            UUID userId = UUID.fromString(rawUserId.trim());
            return chain.filter(exchange)
                    .contextWrite(ctx -> {
                        String tenantId = ctx.getOrDefault(dev.osc.metadata.TenantContext.TENANT_ID_KEY, null);
                        if (tenantId == null) return ctx;
                        return ctx.put(SecurityContext.USER_CONTEXT_KEY,
                                new UserContext(userId, UUID.fromString(tenantId)));
                    });
        } catch (IllegalArgumentException e) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
    }
}
