package dev.osc.security;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * Combines ownership and sharing rule evaluation to decide record-level access.
 * Short-circuits on ownership: if the user owns the record, no sharing lookup is made.
 */
@Component
public class RecordAccessEvaluator {

    private final OwnershipEvaluator ownershipEvaluator;
    private final SharingRuleEvaluator sharingRuleEvaluator;

    public RecordAccessEvaluator(OwnershipEvaluator ownershipEvaluator,
                                  SharingRuleEvaluator sharingRuleEvaluator) {
        this.ownershipEvaluator = ownershipEvaluator;
        this.sharingRuleEvaluator = sharingRuleEvaluator;
    }

    public Mono<Boolean> canAccess(UUID tenantId, UUID userId, Map<String, Object> record) {
        if (ownershipEvaluator.isOwner(userId, record)) {
            return Mono.just(true);
        }
        Object recordId = record.get("id");
        if (recordId == null) {
            return Mono.just(false);
        }
        return sharingRuleEvaluator.hasSharedAccess(tenantId, userId, UUID.fromString(recordId.toString()));
    }
}
