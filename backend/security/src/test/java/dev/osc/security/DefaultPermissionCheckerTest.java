package dev.osc.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * TDD — written before DefaultPermissionChecker exists.
 * Strategy: mock PermissionRepository to isolate business logic.
 */
class DefaultPermissionCheckerTest {

    private PermissionRepository permissionRepository;
    private DefaultPermissionChecker checker;

    private static final UUID TENANT_ID = UUID.fromString("11111111-0000-0000-0000-000000000000");
    private static final UUID USER_ID   = UUID.fromString("22222222-0000-0000-0000-000000000000");
    private static final UUID PS_ID     = UUID.fromString("33333333-0000-0000-0000-000000000000");
    private static final UUID OBJ_ID    = UUID.fromString("44444444-0000-0000-0000-000000000000");
    private static final UUID FIELD_ID  = UUID.fromString("55555555-0000-0000-0000-000000000000");

    @BeforeEach
    void setUp() {
        permissionRepository = mock(PermissionRepository.class);
        checker = new DefaultPermissionChecker(permissionRepository);
    }

    // ── canRead ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("canRead returns true when permission set grants read")
    void canRead_granted() {
        ObjectPermission perm = new ObjectPermission(OBJ_ID, PS_ID, true, false, false, false);
        when(permissionRepository.findObjectPermissionsForUser(TENANT_ID, USER_ID, "Account"))
                .thenReturn(Flux.just(perm));

        StepVerifier.create(checker.canRead(TENANT_ID, USER_ID, "Account"))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    @DisplayName("canRead returns false when no permission set grants read")
    void canRead_denied() {
        ObjectPermission perm = new ObjectPermission(OBJ_ID, PS_ID, false, false, false, false);
        when(permissionRepository.findObjectPermissionsForUser(TENANT_ID, USER_ID, "Account"))
                .thenReturn(Flux.just(perm));

        StepVerifier.create(checker.canRead(TENANT_ID, USER_ID, "Account"))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    @DisplayName("canRead returns false when user has no permission sets")
    void canRead_noPermissionSets() {
        when(permissionRepository.findObjectPermissionsForUser(TENANT_ID, USER_ID, "Account"))
                .thenReturn(Flux.empty());

        StepVerifier.create(checker.canRead(TENANT_ID, USER_ID, "Account"))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    @DisplayName("canRead returns true if ANY permission set grants read (union semantics)")
    void canRead_unionSemantics() {
        ObjectPermission denied  = new ObjectPermission(OBJ_ID, PS_ID, false, false, false, false);
        UUID ps2 = UUID.fromString("66666666-0000-0000-0000-000000000000");
        ObjectPermission granted = new ObjectPermission(OBJ_ID, ps2,  true,  false, false, false);
        when(permissionRepository.findObjectPermissionsForUser(TENANT_ID, USER_ID, "Account"))
                .thenReturn(Flux.just(denied, granted));

        StepVerifier.create(checker.canRead(TENANT_ID, USER_ID, "Account"))
                .expectNext(true)
                .verifyComplete();
    }

    // ── canCreate / canEdit / canDelete ───────────────────────────────────────

    @Test
    @DisplayName("canCreate returns true when permission set grants create")
    void canCreate_granted() {
        ObjectPermission perm = new ObjectPermission(OBJ_ID, PS_ID, true, true, false, false);
        when(permissionRepository.findObjectPermissionsForUser(TENANT_ID, USER_ID, "Account"))
                .thenReturn(Flux.just(perm));

        StepVerifier.create(checker.canCreate(TENANT_ID, USER_ID, "Account"))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    @DisplayName("canDelete returns false when permission set denies delete")
    void canDelete_denied() {
        ObjectPermission perm = new ObjectPermission(OBJ_ID, PS_ID, true, true, true, false);
        when(permissionRepository.findObjectPermissionsForUser(TENANT_ID, USER_ID, "Account"))
                .thenReturn(Flux.just(perm));

        StepVerifier.create(checker.canDelete(TENANT_ID, USER_ID, "Account"))
                .expectNext(false)
                .verifyComplete();
    }

    // ── allowedReadFields ─────────────────────────────────────────────────────

    @Test
    @DisplayName("allowedReadFields returns union of readable field API names across permission sets")
    void allowedReadFields_union() {
        FieldPermission fp1 = new FieldPermission(FIELD_ID, PS_ID, "name", true, false);
        UUID f2 = UUID.fromString("77777777-0000-0000-0000-000000000000");
        FieldPermission fp2 = new FieldPermission(f2, PS_ID, "industry", true, true);
        when(permissionRepository.findFieldPermissionsForUser(TENANT_ID, USER_ID, "Account"))
                .thenReturn(Flux.just(fp1, fp2));

        StepVerifier.create(checker.allowedReadFields(TENANT_ID, USER_ID, "Account"))
                .expectNextMatches(set -> set.containsAll(Set.of("name", "industry")))
                .verifyComplete();
    }

    @Test
    @DisplayName("allowedReadFields excludes fields where can_read is false")
    void allowedReadFields_excludesDenied() {
        FieldPermission fp1 = new FieldPermission(FIELD_ID, PS_ID, "name", true, false);
        UUID f2 = UUID.fromString("77777777-0000-0000-0000-000000000000");
        FieldPermission fp2 = new FieldPermission(f2, PS_ID, "secret", false, false);
        when(permissionRepository.findFieldPermissionsForUser(TENANT_ID, USER_ID, "Account"))
                .thenReturn(Flux.just(fp1, fp2));

        StepVerifier.create(checker.allowedReadFields(TENANT_ID, USER_ID, "Account"))
                .expectNextMatches(set -> set.contains("name") && !set.contains("secret"))
                .verifyComplete();
    }

    @Test
    @DisplayName("allowedReadFields returns empty set when no field permissions exist")
    void allowedReadFields_empty() {
        when(permissionRepository.findFieldPermissionsForUser(TENANT_ID, USER_ID, "Account"))
                .thenReturn(Flux.empty());

        StepVerifier.create(checker.allowedReadFields(TENANT_ID, USER_ID, "Account"))
                .expectNextMatches(Set::isEmpty)
                .verifyComplete();
    }
}
