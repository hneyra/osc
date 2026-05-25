package dev.osc.api.observability;

import dev.osc.metadata.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Records osc.http.requests counter with tags: tenant_id, method, status, uri.
 * Runs after TenantContextFilter (@Order(1)) so the tenantId is already in context.
 */
@Component
@Order(10)
public class TenantMetricsFilter implements WebFilter {

    static final String METRIC_NAME = "osc.http.requests";

    private final MeterRegistry registry;

    public TenantMetricsFilter(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String method = exchange.getRequest().getMethod().name();
        String uri = exchange.getRequest().getURI().getPath();

        return chain.filter(exchange)
                .doOnEach(signal -> {
                    if (!signal.isOnComplete() && !signal.isOnError()) {
                        return;
                    }
                    String tenantId = signal.getContextView()
                            .getOrDefault(TenantContext.TENANT_ID_KEY, "unknown");
                    String status = exchange.getResponse().getStatusCode() != null
                            ? String.valueOf(exchange.getResponse().getStatusCode().value())
                            : "200";

                    registry.counter(METRIC_NAME,
                            "tenant_id", tenantId,
                            "method", method,
                            "status", status,
                            "uri", uri
                    ).increment();
                });
    }
}
