package dev.osc.api.observability;

import dev.osc.metadata.TenantContext;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

/**
 * Populates MDC with tenantId and correlationId for each request so that
 * the logback encoder can emit structured JSON with those fields.
 *
 * Uses doOnEach to hook into the reactive signal lifecycle rather than
 * ThreadLocal directly, which is incompatible with non-blocking schedulers.
 */
@Component
@Order(5)
public class TenantAwareMdcFilter implements WebFilter {

    static final String MDC_TENANT_ID = "tenantId";
    static final String MDC_CORRELATION_ID = "correlationId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return chain.filter(exchange)
                .doOnEach(signal -> {
                    if (!signal.isOnComplete() && !signal.isOnError() && !signal.isOnNext()) {
                        return;
                    }
                    ContextView ctx = signal.getContextView();
                    String tenantId = ctx.getOrDefault(TenantContext.TENANT_ID_KEY, null);
                    String correlationId = ctx.getOrDefault(CorrelationIdFilter.CORRELATION_ID_KEY, null);

                    if (tenantId != null) {
                        MDC.put(MDC_TENANT_ID, tenantId);
                    }
                    if (correlationId != null) {
                        MDC.put(MDC_CORRELATION_ID, correlationId);
                    }
                })
                .doFinally(signalType -> {
                    MDC.remove(MDC_TENANT_ID);
                    MDC.remove(MDC_CORRELATION_ID);
                });
    }
}
