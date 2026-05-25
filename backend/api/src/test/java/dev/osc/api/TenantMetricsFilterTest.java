package dev.osc.api;

import dev.osc.api.observability.TenantMetricsFilter;
import dev.osc.metadata.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TenantMetricsFilter")
class TenantMetricsFilterTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final TenantMetricsFilter filter = new TenantMetricsFilter(registry);

    @Test
    @DisplayName("increments osc.http.requests counter with tenant, method, uri tags")
    void incrementsCounterPerTenant() {
        String tenantId = UUID.randomUUID().toString();
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/records").build());

        var chain = (org.springframework.web.server.WebFilterChain) ex -> Mono.empty();

        StepVerifier.create(
                filter.filter(exchange, chain)
                        .contextWrite(ctx -> ctx.put(TenantContext.TENANT_ID_KEY, tenantId))
        ).verifyComplete();

        Counter counter = registry.find("osc.http.requests")
                .tag("tenant_id", tenantId)
                .tag("method", "GET")
                .tag("uri", "/api/v1/records")
                .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("records different counters for different tenants")
    void differentCountersForDifferentTenants() {
        String tenant1 = UUID.randomUUID().toString();
        String tenant2 = UUID.randomUUID().toString();
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/records").build());

        var chain = (org.springframework.web.server.WebFilterChain) ex -> Mono.empty();

        StepVerifier.create(
                filter.filter(exchange, chain)
                        .contextWrite(ctx -> ctx.put(TenantContext.TENANT_ID_KEY, tenant1))
        ).verifyComplete();

        StepVerifier.create(
                filter.filter(exchange, chain)
                        .contextWrite(ctx -> ctx.put(TenantContext.TENANT_ID_KEY, tenant2))
        ).verifyComplete();

        assertThat(registry.find("osc.http.requests").tag("tenant_id", tenant1).counter())
                .isNotNull();
        assertThat(registry.find("osc.http.requests").tag("tenant_id", tenant2).counter())
                .isNotNull();
    }

    @Test
    @DisplayName("uses 'unknown' tenant tag when tenantId absent from context")
    void unknownTenantWhenMissingContext() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/objects").build());

        var chain = (org.springframework.web.server.WebFilterChain) ex -> Mono.empty();

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        Counter counter = registry.find("osc.http.requests")
                .tag("tenant_id", "unknown")
                .tag("method", "POST")
                .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }
}
