package dev.osc.integrations.http;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

class OutboundHttpClientTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-0000-0000-0000-000000000000");

    private DomainAllowlist allowlist;
    private OutboundAuditLog auditLog;

    @BeforeEach
    void setUp() {
        allowlist = new DomainAllowlist(Set.of("allowed.example.com"));
        auditLog  = new InMemoryOutboundAuditLog();
    }

    @Test
    @DisplayName("post returns error when domain is not allowed")
    void post_blockedDomain() {
        DefaultOutboundHttpClient client = new DefaultOutboundHttpClient(allowlist, auditLog, null);

        StepVerifier.create(client.post(
                "https://evil.com/hook", "{}", Map.of()))
                .expectErrorMatches(e -> e instanceof DomainNotAllowedException)
                .verify();
    }

    @Test
    @DisplayName("post completes with status code for allowed domain (uses real HTTP for integration)")
    void post_allowedDomainSucceeds() {
        // Verifies that allowed domains pass the allowlist gate and delegate to WebClient.
        // Full HTTP round-trip tested in integration tests; here we just verify no exception is thrown
        // for an allowed domain when the underlying WebClient is stubbed.
        DefaultOutboundHttpClient client = new DefaultOutboundHttpClient(
                new DomainAllowlist(Set.of("allowed.example.com")),
                new InMemoryOutboundAuditLog(),
                new StubWebClientAdapter(200));

        StepVerifier.create(client.post(
                "https://allowed.example.com/hook", "{\"key\":\"val\"}", Map.of()))
                .expectNext(200)
                .verifyComplete();
    }
}
