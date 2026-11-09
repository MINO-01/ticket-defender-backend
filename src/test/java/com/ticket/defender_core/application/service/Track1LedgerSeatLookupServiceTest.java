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

    @Test
    void neverRehashesLegacyTrack1QuantityEvidenceAsATrack2ReservationReference() {
        Fixture fixture = fixture(true);
        when(fixture.member.getReferencePurpose()).thenReturn("LEGACY_V1_UNLINKABLE");
        // This value looks like an HMAC, but no purpose declaration authorizes using it for Track 2 linkage.
        when(fixture.member.getReservationNo()).thenReturn(new TenantPurposeReferenceHasher(KEY, "TRACK1_QTY_RESERVATION", "v1")
                .hash(fixture.tenantId, "same-reservation"));

        assertUnavailable(fixture.service.lookup(fixture.tenantId, fixture.eventId, fixture.performanceId,
                fixture.sourceVersion, fixture.batchId, null, List.of(fixture.member)));
    }

    @Test
    void returnsLedgerUnavailableWhenTheBoundCandidateRowIsMissing() {
        Fixture fixture = fixture(true);
        UUID candidateId = UUID.randomUUID();
        when(fixture.candidates.findById(candidateId)).thenReturn(Optional.empty());

        assertUnavailable(lookupTarget(fixture, candidateId));
    }

    @Test
    void returnsLedgerUnavailableWhenTheBoundCandidateBelongsToAnotherBatch() {
        Fixture fixture = fixture(true);
        UUID candidateId = target(fixture, UUID.randomUUID(), "any-reference");

        assertUnavailable(lookupTarget(fixture, candidateId));
    }

    @Test
    void reportsAResolvedCandidateWithADifferentReferenceAsARealMissEvenWhenMembersHaveSeats() {
        Fixture fixture = fixture(true);
        UUID candidateId = target(fixture, fixture.batchId, "different-reference");

        Track1LedgerSeatLookupService.Result result = lookupTarget(fixture, candidateId);

        assertThat(result.ledgerAvailable()).isTrue();
        assertThat(result.targetReferenceFound()).isFalse();
        assertThat(result.seats()).extracting(Track1LedgerSeatLookupService.LedgerSeat::status)
                .containsExactly(Track1LedgerSeatLookupService.Status.FOUND);
    }

    @Test
    void reportsTargetFoundOnlyForAnExactMemberReferenceMatch() {
        Fixture fixture = fixture(true);
        UUID candidateId = target(fixture, fixture.batchId,
                new TenantPurposeReferenceHasher(KEY, "v1").hash(fixture.tenantId, "track1-reservation"));

        Track1LedgerSeatLookupService.Result result = lookupTarget(fixture, candidateId);

        assertThat(result.ledgerAvailable()).isTrue();
        assertThat(result.targetReferenceFound()).isTrue();
    }

    @Test
    void returnsEverySeatForOneReservationReferenceWithoutMapKeyCollision() {
        Fixture fixture = fixture(true);
        ReservationCandidate second = mock(ReservationCandidate.class);
        when(second.getReservationRefHmac()).thenReturn(new TenantPurposeReferenceHasher(KEY, "v1")
                .hash(fixture.tenantId, "track1-reservation"));
        when(second.getZone()).thenReturn("A"); when(second.getRowNum()).thenReturn("1"); when(second.getSeatNum()).thenReturn("3");
        when(second.getSourceRowIndex()).thenReturn(1);
        ReservationCandidate first = mock(ReservationCandidate.class);
        when(first.getReservationRefHmac()).thenReturn(new TenantPurposeReferenceHasher(KEY, "v1")
                .hash(fixture.tenantId, "track1-reservation"));
        when(first.getZone()).thenReturn("A"); when(first.getRowNum()).thenReturn("1"); when(first.getSeatNum()).thenReturn("2");
        when(first.getSourceRowIndex()).thenReturn(0);
        when(fixture.candidates.findByBatchIdAndReservationRefHmacIn(eq(fixture.batchId), any())).thenReturn(List.of(second, first));

        Track1LedgerSeatLookupService.Result result = lookupTarget(fixture, null);

        assertThat(result.seats()).singleElement().satisfies(seat -> {
            assertThat(seat.status()).isEqualTo(Track1LedgerSeatLookupService.Status.FOUND);
            assertThat(seat.zone()).isNull();
            assertThat(seat.seats()).extracting(Track1LedgerSeatLookupService.Seat::seat).containsExactly("2", "3");
        });
    }

    private static UUID target(Fixture fixture, UUID batchId, String reference) {
        UUID candidateId = UUID.randomUUID();
        ReservationCandidate target = mock(ReservationCandidate.class);
        when(target.getBatchId()).thenReturn(batchId);
        when(target.getReservationRefHmac()).thenReturn(reference);
        when(fixture.candidates.findById(candidateId)).thenReturn(Optional.of(target));
        return candidateId;
    }

    private static Track1LedgerSeatLookupService.Result lookupTarget(Fixture fixture, UUID candidateId) {
        return fixture.service.lookup(fixture.tenantId, fixture.eventId, fixture.performanceId,
                fixture.sourceVersion, fixture.batchId, candidateId, List.of(fixture.member));
    }

    private static void assertUnavailable(Track1LedgerSeatLookupService.Result result) {
        assertThat(result.ledgerAvailable()).isFalse();
        assertThat(result.targetReferenceFound()).isFalse();
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
        Track1LedgerSeatLookupService service = new Track1LedgerSeatLookupService(
                new Track1LedgerSnapshotReader(candidates, batches, sources), gate);

        MacroAuditEvidence member = mock(MacroAuditEvidence.class);
        when(member.getAuditId()).thenReturn(17L);
        when(member.getReferencePurpose()).thenReturn(TenantPurposeReferenceHasher.RESERVATION_REFERENCE_PURPOSE);
        when(member.getReservationNo()).thenReturn(hasher.hash(tenantId, "track1-reservation"));
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
        return new Fixture(service, candidates, tenantId, eventId, performanceId, batchId, sourceVersion, member);
    }

    private record Fixture(Track1LedgerSeatLookupService service, ReservationCandidateRepository candidates,
                           UUID tenantId, UUID eventId, UUID performanceId, UUID batchId,
                           String sourceVersion, MacroAuditEvidence member) { }
}
