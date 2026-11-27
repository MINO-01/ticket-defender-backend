package com.ticket.defender_core.application.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class InvestigationReferenceCipherTest {
    private static final UUID IMPORT = UUID.fromString("15a7ad68-91ed-4d80-b291-a5c304fd558a");
    private static final UUID TENANT = UUID.fromString("26163970-cf14-482a-a9f6-0c815e092f2e");
    private static final UUID EVENT = UUID.fromString("3eff5c55-8d05-42de-90ba-646377c1bb27");
    private static final UUID PERFORMANCE = UUID.fromString("24653ae2-ae48-453f-8ad7-e29bd8f3ed1e");

    @Test
    void encryptsAndDecryptsReferencesUsingFreshNonceAndTenantBoundAad() {
        InvestigationReferenceCipher cipher = cipher(true);
        var first = cipher.encrypt(IMPORT, TENANT, EVENT, PERFORMANCE, 7, "t".repeat(64), "r".repeat(64), "a".repeat(64),
                "synthetic-account-12", "synthetic-reservation-23", "synthetic-ticket-34");
        var second = cipher.encrypt(IMPORT, TENANT, EVENT, PERFORMANCE, 7, "t".repeat(64), "r".repeat(64), "a".repeat(64),
                "synthetic-account-12", "synthetic-reservation-23", "synthetic-ticket-34");

        assertNotNull(first);
        assertFalse(java.util.Arrays.equals(first.nonce(), second.nonce()));
        assertFalse(java.util.Arrays.equals(first.ciphertext(), second.ciphertext()));
        var decoded = cipher.decrypt(IMPORT, TENANT, EVENT, PERFORMANCE, 7, "t".repeat(64), "r".repeat(64), "a".repeat(64), first);
        assertEquals(new InvestigationReferenceCipher.References("synthetic-account-12", "synthetic-reservation-23", "synthetic-ticket-34"), decoded);
        assertFalse(decoded.toString().contains("synthetic-account-12"));
        assertFalse(first.toString().contains("synthetic-account-12"));
        assertThrows(RuntimeException.class, () -> cipher.decrypt(IMPORT, UUID.randomUUID(), EVENT, PERFORMANCE, 7,
                "t".repeat(64), "r".repeat(64), "a".repeat(64), first));
    }

    @Test
    void rejectsUnavailableKeyVersionAndTamperedCiphertext() {
        InvestigationReferenceCipher cipher = cipher(true);
        var encrypted = cipher.encrypt(IMPORT, TENANT, EVENT, PERFORMANCE, 1, "t", "r", "a", "acct", "res", "ticket");
        byte[] changed = encrypted.ciphertext();
        changed[0] ^= 1;
        var tampered = new InvestigationReferenceCipher.Encrypted(encrypted.keyVersion(), encrypted.nonce(), changed);
        assertThrows(RuntimeException.class, () -> cipher.decrypt(IMPORT, TENANT, EVENT, PERFORMANCE, 1, "t", "r", "a", tampered));

        var oldVersion = new InvestigationReferenceCipher.Encrypted("old-key", encrypted.nonce(), encrypted.ciphertext());
        RuntimeException unavailable = assertThrows(RuntimeException.class,
                () -> cipher.decrypt(IMPORT, TENANT, EVENT, PERFORMANCE, 1, "t", "r", "a", oldVersion));
        assertTrue(unavailable.getMessage().contains("INVESTIGATION_REVEAL_KEY_VERSION_UNAVAILABLE"));
    }

    @Test
    void disabledFeatureCreatesNoCiphertext() {
        InvestigationReferenceCipher cipher = cipher(false);
        assertNull(cipher.encrypt(IMPORT, TENANT, EVENT, PERFORMANCE, 1, "t", "r", "a", "acct", "res", "ticket"));
        CaseApiException error = assertThrows(CaseApiException.class,
                () -> cipher.decrypt(IMPORT, TENANT, EVENT, PERFORMANCE, 1, "t", "r", "a",
                        new InvestigationReferenceCipher.Encrypted("test-v1", new byte[12], new byte[16])));
        assertEquals(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, error.status());
    }

    @Test
    void unavailableKmsDisablesOnlyEncryptionAndDoesNotExposePlainReferences() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        InvestigationReferenceCipher cipher = new InvestigationReferenceCipher(
                beans.getBeanProvider(InvestigationRevealKeySource.class), true, "KMS", "", "");
        assertNull(cipher.encrypt(IMPORT, TENANT, EVENT, PERFORMANCE, 1, "t", "r", "a", "acct", "res", "ticket"));
    }

    @Test
    void syntheticConfigDerivesAWorkingTenantScopedKey() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        String master = java.util.Base64.getEncoder().encodeToString(new byte[32]);
        InvestigationReferenceCipher cipher = new InvestigationReferenceCipher(
                beans.getBeanProvider(InvestigationRevealKeySource.class), true, "SYNTHETIC_CONFIG", master, "synthetic-v1");
        var encrypted = cipher.encrypt(IMPORT, TENANT, EVENT, PERFORMANCE, 2, "ticket-hmac", "reservation-hmac", "account-hmac",
                "synthetic-account", "synthetic-reservation", "synthetic-ticket");
        assertNotNull(encrypted);
        assertEquals("synthetic-account", cipher.decrypt(IMPORT, TENANT, EVENT, PERFORMANCE, 2,
                "ticket-hmac", "reservation-hmac", "account-hmac", encrypted).accountReference());
    }

    private static InvestigationReferenceCipher cipher(boolean enabled) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("investigationRevealKeySource", new InvestigationRevealKeySource() {
            @Override public String currentKeyVersion() { return "test-v1"; }
            @Override public byte[] tenantDataKey(UUID tenantId, String keyVersion) {
                if (!"test-v1".equals(keyVersion)) return null;
                byte[] key = new byte[32];
                java.util.Arrays.fill(key, (byte) tenantId.hashCode());
                return key;
            }
        });
        return new InvestigationReferenceCipher(beans.getBeanProvider(InvestigationRevealKeySource.class), enabled,
                "KMS", "", "test-v1");
    }
}
