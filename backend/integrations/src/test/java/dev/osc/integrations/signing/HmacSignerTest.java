package dev.osc.integrations.signing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HmacSignerTest {

    private final HmacSigner signer = new HmacSigner();

    @Test
    @DisplayName("sign produces deterministic hex HMAC-SHA256")
    void sign_deterministic() {
        String sig1 = signer.sign("hello world", "secret-key");
        String sig2 = signer.sign("hello world", "secret-key");
        assertEquals(sig1, sig2);
        assertFalse(sig1.isBlank());
    }

    @Test
    @DisplayName("sign is 64 hex characters (256-bit = 32 bytes)")
    void sign_correctLength() {
        String sig = signer.sign("payload", "key");
        assertEquals(64, sig.length());
        assertTrue(sig.matches("[0-9a-f]{64}"));
    }

    @Test
    @DisplayName("different payloads produce different signatures")
    void sign_differentPayloads() {
        String s1 = signer.sign("payload-a", "key");
        String s2 = signer.sign("payload-b", "key");
        assertNotEquals(s1, s2);
    }

    @Test
    @DisplayName("different secrets produce different signatures")
    void sign_differentSecrets() {
        String s1 = signer.sign("same payload", "key-a");
        String s2 = signer.sign("same payload", "key-b");
        assertNotEquals(s1, s2);
    }

    @Test
    @DisplayName("verifySignature returns true for matching signature")
    void verifySignature_match() {
        String payload = "test-body";
        String secret  = "my-secret";
        String sig     = signer.sign(payload, secret);
        assertTrue(signer.verifySignature(payload, secret, sig));
    }

    @Test
    @DisplayName("verifySignature returns false for tampered signature")
    void verifySignature_mismatch() {
        String sig = signer.sign("original", "secret");
        assertFalse(signer.verifySignature("tampered", "secret", sig));
    }
}
