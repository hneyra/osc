package dev.osc.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.*;

/**
 * TDD — covers OwnershipEvaluator, SharingRuleEvaluator, and RecordAccessEvaluator.
 */
class RecordAccessEvaluatorTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-0000-0000-0000-000000000000");
    private static final UUID USER_ID   = UUID.fromString("22222222-0000-0000-0000-000000000000");
    private static final UUID REC_ID    = UUID.fromString("33333333-0000-0000-0000-000000000000");

    // ── OwnershipEvaluator ────────────────────────────────────────────────────

    private OwnershipEvaluator ownershipEvaluator;

    @BeforeEach
    void setUp() {
        ownershipEvaluator = new OwnershipEvaluator();
    }

    @Test
    @DisplayName("isOwner returns true when ownerId matches userId")
    void isOwner_match() {
        Map<String, Object> record = Map.of("id", REC_ID.toString(), "ownerId", USER_ID.toString());
        org.junit.jupiter.api.Assertions.assertTrue(ownershipEvaluator.isOwner(USER_ID, record));
    }

    @Test
    @DisplayName("isOwner returns false when ownerId differs from userId")
    void isOwner_mismatch() {
        UUID otherId = UUID.fromString("99999999-0000-0000-0000-000000000000");
        Map<String, Object> record = Map.of("id", REC_ID.toString(), "ownerId", otherId.toString());
        org.junit.jupiter.api.Assertions.assertFalse(ownershipEvaluator.isOwner(USER_ID, record));
    }

    @Test
    @DisplayName("isOwner returns false when record has no ownerId")
    void isOwner_noOwnerId() {
        Map<String, Object> record = Map.of("id", REC_ID.toString());
        org.junit.jupiter.api.Assertions.assertFalse(ownershipEvaluator.isOwner(USER_ID, record));
    }

    // ── SharingRuleEvaluator ──────────────────────────────────────────────────

    @Test
    @DisplayName("SharingRuleEvaluator.hasSharedAccess returns false in Phase 4 stub")
    void sharingRule_stubReturnsFalse() {
        SharingRuleEvaluator evaluator = new SharingRuleEvaluator();
        StepVerifier.create(evaluator.hasSharedAccess(TENANT_ID, USER_ID, REC_ID))
                .expectNext(false)
                .verifyComplete();
    }

    // ── RecordAccessEvaluator ─────────────────────────────────────────────────

    @Test
    @DisplayName("canAccess returns true when user is owner")
    void canAccess_owner() {
        SharingRuleEvaluator sharingEval = mock(SharingRuleEvaluator.class);
        RecordAccessEvaluator evaluator = new RecordAccessEvaluator(ownershipEvaluator, sharingEval);

        Map<String, Object> record = Map.of(
                "id", REC_ID.toString(),
                "ownerId", USER_ID.toString()
        );

        StepVerifier.create(evaluator.canAccess(TENANT_ID, USER_ID, record))
                .expectNext(true)
                .verifyComplete();

        verifyNoInteractions(sharingEval); // short-circuit: owner check should avoid sharing lookup
    }

    @Test
    @DisplayName("canAccess returns true when user has shared access")
    void canAccess_sharedAccess() {
        SharingRuleEvaluator sharingEval = mock(SharingRuleEvaluator.class);
        UUID otherId = UUID.fromString("99999999-0000-0000-0000-000000000000");
        when(sharingEval.hasSharedAccess(TENANT_ID, USER_ID, REC_ID))
                .thenReturn(Mono.just(true));
        RecordAccessEvaluator evaluator = new RecordAccessEvaluator(ownershipEvaluator, sharingEval);

        Map<String, Object> record = Map.of(
                "id", REC_ID.toString(),
                "ownerId", otherId.toString() // not owner
        );

        StepVerifier.create(evaluator.canAccess(TENANT_ID, USER_ID, record))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    @DisplayName("canAccess returns false when not owner and no shared access")
    void canAccess_denied() {
        SharingRuleEvaluator sharingEval = mock(SharingRuleEvaluator.class);
        UUID otherId = UUID.fromString("99999999-0000-0000-0000-000000000000");
        when(sharingEval.hasSharedAccess(TENANT_ID, USER_ID, REC_ID))
                .thenReturn(Mono.just(false));
        RecordAccessEvaluator evaluator = new RecordAccessEvaluator(ownershipEvaluator, sharingEval);

        Map<String, Object> record = Map.of(
                "id", REC_ID.toString(),
                "ownerId", otherId.toString()
        );

        StepVerifier.create(evaluator.canAccess(TENANT_ID, USER_ID, record))
                .expectNext(false)
                .verifyComplete();
    }
}
