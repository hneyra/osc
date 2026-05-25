package dev.osc.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.*;

/**
 * TDD — written before FlsFilter exists.
 */
class FlsFilterTest {

    private PermissionChecker permissionChecker;
    private FlsFilter flsFilter;

    private static final UUID TENANT_ID = UUID.fromString("11111111-0000-0000-0000-000000000000");
    private static final UUID USER_ID   = UUID.fromString("22222222-0000-0000-0000-000000000000");

    @BeforeEach
    void setUp() {
        permissionChecker = mock(PermissionChecker.class);
        flsFilter = new FlsFilter(permissionChecker);
    }

    @Test
    @DisplayName("strips forbidden fields from record map")
    void stripsForbiddenFields() {
        when(permissionChecker.allowedReadFields(TENANT_ID, USER_ID, "Account"))
                .thenReturn(Mono.just(Set.of("name", "industry")));

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", "rec-1");
        record.put("name", "Acme");
        record.put("industry", "Tech");
        record.put("secret", "classified");

        StepVerifier.create(flsFilter.apply(record, TENANT_ID, USER_ID, "Account"))
                .expectNextMatches(result ->
                        result.containsKey("name") &&
                        result.containsKey("industry") &&
                        !result.containsKey("secret") &&
                        result.containsKey("id") // system fields always pass through
                )
                .verifyComplete();
    }

    @Test
    @DisplayName("system fields (id, objectId, createdAt, updatedAt, ownerId) always pass through")
    void systemFieldsPassThrough() {
        when(permissionChecker.allowedReadFields(TENANT_ID, USER_ID, "Account"))
                .thenReturn(Mono.just(Set.of("name")));

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", "rec-1");
        record.put("objectId", "obj-1");
        record.put("ownerId", "user-1");
        record.put("createdAt", "2024-01-01");
        record.put("updatedAt", "2024-01-02");
        record.put("name", "Acme");
        record.put("secret", "hidden");

        StepVerifier.create(flsFilter.apply(record, TENANT_ID, USER_ID, "Account"))
                .expectNextMatches(result ->
                        result.containsKey("id") &&
                        result.containsKey("objectId") &&
                        result.containsKey("ownerId") &&
                        result.containsKey("createdAt") &&
                        result.containsKey("updatedAt") &&
                        result.containsKey("name") &&
                        !result.containsKey("secret")
                )
                .verifyComplete();
    }

    @Test
    @DisplayName("empty allowed fields means no field permissions defined — record passes through unchanged")
    void emptyAllowedFields_passThrough() {
        when(permissionChecker.allowedReadFields(TENANT_ID, USER_ID, "Account"))
                .thenReturn(Mono.just(Set.of()));

        Map<String, Object> record = Map.of("id", "1", "name", "Acme", "secret", "data");

        StepVerifier.create(flsFilter.apply(record, TENANT_ID, USER_ID, "Account"))
                .expectNextMatches(result -> result.equals(record))
                .verifyComplete();
    }

    @Test
    @DisplayName("apply is a pure function — original map is not modified")
    void doesNotMutateInput() {
        when(permissionChecker.allowedReadFields(TENANT_ID, USER_ID, "Account"))
                .thenReturn(Mono.just(Set.of("name")));

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", "1");
        record.put("name", "Acme");
        record.put("secret", "data");
        int originalSize = record.size();

        StepVerifier.create(flsFilter.apply(record, TENANT_ID, USER_ID, "Account"))
                .expectNextCount(1)
                .verifyComplete();

        org.junit.jupiter.api.Assertions.assertEquals(originalSize, record.size(),
                "Original record must not be mutated");
    }
}
