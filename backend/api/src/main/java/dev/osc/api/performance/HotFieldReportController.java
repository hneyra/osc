package dev.osc.api.performance;

import dev.osc.metadata.TenantContext;
import dev.osc.metadata.performance.FieldAccessCounter;
import dev.osc.metadata.performance.HotFieldReport;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Admin endpoint to inspect hot-field access data for a given object.
 * Returns the top-10 most accessed fields for the current tenant.
 */
@RestController
@RequestMapping("/api/v1/admin/hot-fields")
public class HotFieldReportController {

    private static final int TOP_N = 10;

    private final FieldAccessCounter fieldAccessCounter;

    public HotFieldReportController(FieldAccessCounter fieldAccessCounter) {
        this.fieldAccessCounter = fieldAccessCounter;
    }

    @GetMapping("/{objectApiName}")
    public Mono<ResponseEntity<HotFieldReport>> getHotFields(
            @PathVariable String objectApiName) {
        return Mono.deferContextual(ctx -> {
            String tenantId = ctx.getOrDefault(TenantContext.TENANT_ID_KEY, null);
            if (tenantId == null) {
                return Mono.just(ResponseEntity.status(401).<HotFieldReport>build());
            }
            HotFieldReport report = fieldAccessCounter.topFields(
                    UUID.fromString(tenantId), objectApiName, TOP_N);
            return Mono.just(ResponseEntity.ok(report));
        });
    }
}
