package dev.osc.security;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Evaluates sharing rules for record-level access.
 * Phase 4 stub: sharing rules table and engine are out of scope; always returns false.
 * Phase 5+ will replace with a full sharing rule engine.
 */
@Component
public class SharingRuleEvaluator {

    public Mono<Boolean> hasSharedAccess(UUID tenantId, UUID userId, UUID recordId) {
        return Mono.just(false);
    }
}
