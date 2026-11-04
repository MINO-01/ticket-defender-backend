package com.ticket.defender_core.application.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TenantPurposeReferenceHasherTest {
    private static final String MASTER_KEY = "synthetic-only-master-key-material-that-is-long-enough";

    @Test
    void derivesDifferentDigestsForTenantPurposeAndKeyVersion() {
        UUID tenantA = UUID.fromString("11111111-1111-4111-8111-111111111111");
        UUID tenantB = UUID.fromString("22222222-2222-4222-8222-222222222222");
        TenantPurposeReferenceHasher v1 = new TenantPurposeReferenceHasher(MASTER_KEY, "v1");
        TenantPurposeReferenceHasher v2 = new TenantPurposeReferenceHasher(MASTER_KEY, "v2");

        String tenantAValue = v1.hash(tenantA, "reservation-100");

        assertThat(tenantAValue).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(v1.hash(tenantA, "reservation-100")).isEqualTo(tenantAValue);
        assertThat(v1.hash(tenantB, "reservation-100")).isNotEqualTo(tenantAValue);
        assertThat(v2.hash(tenantA, "reservation-100")).isNotEqualTo(tenantAValue);
    }

    @Test
    void purposeIsPartOfTheDerivedKeyDomain() {
        UUID tenant = UUID.fromString("11111111-1111-4111-8111-111111111111");
        String reservation = new TenantPurposeReferenceHasher(MASTER_KEY, "v1").hash(tenant, "reservation-100");
        String otherPurpose = new TenantPurposeReferenceHasher(MASTER_KEY, "TRACK2_OTHER_REFERENCE", "v1")
                .hash(tenant, "reservation-100");

        assertThat(otherPurpose).isNotEqualTo(reservation);
    }
}
