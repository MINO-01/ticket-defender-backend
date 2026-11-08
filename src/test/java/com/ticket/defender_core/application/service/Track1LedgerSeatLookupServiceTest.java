package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.persistence.ReservationCandidateRepository;
import com.ticket.defender_core.adapter.out.persistence.ReservationDataSourceRepository;
import com.ticket.defender_core.adapter.out.persistence.ReservationImportBatchRepository;
import com.ticket.defender_core.domain.MacroAuditEvidence;
import com.ticket.defender_core.domain.ReservationCandidate;
import com.ticket.defender_core.domain.ReservationDataSource;
import com.ticket.defender_core.domain.ReservationImportBatch;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Track1LedgerSeatLookupServiceTest {
    private static final String KEY = "synthetic-only-master-key-material-that-is-long-enough";

    @Test
    void returnsFoundOnlyFromTheCurrentCompleteAcceptedScopeAndNeverReturnsReferenceValues() {
        Fixture fixture = fixture(true);

        Track1LedgerSeatLookupService.Result result = fixture.service.lookup(fixture.tenantId, fixture.eventId,
                fixture.performanceId, fixture.sourceVersion, null, null, List.of(fixture.member));

        assertThat(result.ledgerAvailable()).isTrue();
        assertThat(result.seats()).singleElement().satisfies(seat -> {
            assertThat(seat.status()).isEqualTo(Track1LedgerSeatLookupService.Status.FOUND);
            assertThat(seat.zone()).isEqualTo("A");
            assertThat(seat.row()).isEqualTo("1");
            assertThat(seat.seat()).isEqualTo("2");
        });
    }

    @Test
    void returnsNotInLedgerForACompleteLedgerWithoutTheMemberReference() {
        Fixture fixture = fixture(false);

        Track1LedgerSeatLookupService.Result result = fixture.service.lookup(fixture.tenantId, fixture.eventId,
                fixture.performanceId, fixture.sourceVersion, null, null, List.of(fixture.member));

        assertThat(result.ledgerAvailable()).isTrue();
        assertThat(result.seats()).extracting(Track1LedgerSeatLookupService.LedgerSeat::status)
                .containsExactly(Track1LedgerSeatLookupService.Status.NOT_IN_LEDGER);
    }

    @Test
    void returnsLedgerUnavailableWhenTheCurrentSourceVersionDoesNotMatch() {
        Fixture fixture = fixture(true);

        Track1LedgerSeatLookupService.Result result = fixture.service.lookup(fixture.tenantId, fixture.eventId,
                fixture.performanceId, "different-source-version", null, null, List.of(fixture.member));

        assertThat(result.ledgerAvailable()).isFalse();
        assertThat(result.seats()).extracting(Track1LedgerSeatLookupService.LedgerSeat::status)
                .containsExactly(Track1LedgerSeatLookupService.Status.LEDGER_UNAVAILABLE);
    }

    private static Fixture fixture(boolean found) {
        UUID tenantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID performanceId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        String sourceVersion = "ledger-sha256-fixed-v1";
        TenantPurposeReferenceHasher hasher = new TenantPurposeReferenceHasher(KEY, "v1");
        ReservationCandidateRepository candidates = mock(ReservationCandidateRepository.class);
        ReservationImportBatchRepository batches = mock(ReservationImportBatchRepository.class);
        ReservationDataSourceRepository sources = mock(ReservationDataSourceRepository.class);
        SyntheticReservationImportGate gate = mock(SyntheticReservationImportGate.class);
        Track1LedgerSeatLookupService service = new Track1LedgerSeatLookupService(candidates, batches, sources, gate);

        MacroAuditEvidence member = mock(MacroAuditEvidence.class);
        when(member.getAuditId()).thenReturn(17L);
        when(member.getReservationNo()).thenReturn("track1-reservation");
        ReservationDataSource source = mock(ReservationDataSource.class);
        when(source.isCompleteSnapshot()).thenReturn(true);
        when(source.getCurrentBatchId()).thenReturn(batchId);
        when(source.getCurrentSourceVersion()).thenReturn(sourceVersion);
        when(sources.findByTenantIdAndEventIdAndPerformanceId(tenantId, eventId, performanceId))
                .thenReturn(Optional.of(source));
        ReservationImportBatch batch = mock(ReservationImportBatch.class);
        when(batch.getId()).thenReturn(batchId);
        when(batch.getTenantId()).thenReturn(tenantId);
        when(batch.getEventId()).thenReturn(eventId);
        when(batch.getPerformanceId()).thenReturn(performanceId);
        when(batch.getStatus()).thenReturn(ReservationImportBatch.Status.ACCEPTED);
        when(batch.isCompleteSnapshot()).thenReturn(true);
        when(batch.getSourceVersion()).thenReturn(sourceVersion);
        when(batch.getReferenceHmacKeyVersion()).thenReturn("v1");
        when(batches.findById(batchId)).thenReturn(Optional.of(batch));
        when(gate.referenceHasherForRead()).thenReturn(Optional.of(hasher));
        if (found) {
            ReservationCandidate candidate = mock(ReservationCandidate.class);
            when(candidate.getReservationRefHmac()).thenReturn(hasher.hash(tenantId, "track1-reservation"));
            when(candidate.getZone()).thenReturn("A");
            when(candidate.getRowNum()).thenReturn("1");
            when(candidate.getSeatNum()).thenReturn("2");
            when(candidates.findByBatchIdAndReservationRefHmacIn(eq(batchId), any())).thenReturn(List.of(candidate));
        } else {
            when(candidates.findByBatchIdAndReservationRefHmacIn(eq(batchId), any())).thenReturn(List.of());
        }
        return new Fixture(service, tenantId, eventId, performanceId, sourceVersion, member);
    }

    private record Fixture(Track1LedgerSeatLookupService service, UUID tenantId, UUID eventId, UUID performanceId,
                           String sourceVersion, MacroAuditEvidence member) { }
}
