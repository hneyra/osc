package dev.osc.integrations.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DomainAllowlistTest {

    @Test
    @DisplayName("isAllowed returns true for exact domain match")
    void exactMatch() {
        DomainAllowlist list = new DomainAllowlist(Set.of("example.com", "api.acme.com"));
        assertTrue(list.isAllowed("https://example.com/webhook"));
        assertTrue(list.isAllowed("https://api.acme.com/events"));
    }

    @Test
    @DisplayName("isAllowed returns false for domain not in allowlist")
    void notAllowed() {
        DomainAllowlist list = new DomainAllowlist(Set.of("example.com"));
        assertFalse(list.isAllowed("https://evil.com/payload"));
    }

    @Test
    @DisplayName("isAllowed is case-insensitive for domain part")
    void caseInsensitive() {
        DomainAllowlist list = new DomainAllowlist(Set.of("example.com"));
        assertTrue(list.isAllowed("https://EXAMPLE.COM/hook"));
    }

    @Test
    @DisplayName("isAllowed returns false for malformed URL")
    void malformedUrl() {
        DomainAllowlist list = new DomainAllowlist(Set.of("example.com"));
        assertFalse(list.isAllowed("not-a-url"));
    }

    @Test
    @DisplayName("empty allowlist blocks all URLs")
    void emptyAllowlistBlocksAll() {
        DomainAllowlist list = new DomainAllowlist(Set.of());
        assertFalse(list.isAllowed("https://example.com/hook"));
    }
}
