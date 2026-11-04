package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.persistence.SyntheticReservationImportTenantRepository;
import com.ticket.defender_core.domain.SyntheticReservationImportTenant;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SyntheticReservationImportGateTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ISOLATION = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final String KEY = "synthetic-only-master-key-material-that-is-long-enough";

    @Test
    void disabledByDefaultRejectsBeforeAnySyntheticImportCanStart() {
        SyntheticReservationImportGate gate = new SyntheticReservationImportGate(false, "DISABLED", "", KEY, "v1",
                "jdbc:h2:mem:synthetic", mock(SyntheticReservationImportTenantRepository.class));

        assertThatThrownBy(() -> gate.requireSyntheticScope(TENANT, LocalDateTime.now()))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("RESERVATION_IMPORT_DISABLED"));
    }

    @Test
    void requiresAllSyntheticIsolationSignalsBeforeReturningTheRegisteredScope() {
        SyntheticReservationImportTenantRepository registrations = mock(SyntheticReservationImportTenantRepository.class);
        LocalDateTime now = LocalDateTime.parse("2026-11-04T12:00:00");
        when(registrations.findByTenantIdForShare(TENANT)).thenReturn(Optional.of(
                new SyntheticReservationImportTenant(TENANT, ISOLATION, now.minusSeconds(1))));
        SyntheticReservationImportGate gate = new SyntheticReservationImportGate(true, "SYNTHETIC_ISOLATED",
                ISOLATION.toString(), KEY, "v1", "jdbc:h2:mem:synthetic", registrations);

        assertThat(gate.requireSyntheticScope(TENANT, now)).isEqualTo(ISOLATION);
        assertThat(gate.referenceHasher().purpose())
                .isEqualTo(TenantPurposeReferenceHasher.RESERVATION_REFERENCE_PURPOSE);
    }
}
