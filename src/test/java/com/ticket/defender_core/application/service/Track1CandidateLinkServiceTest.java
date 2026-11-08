package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.CandidatePipelineDto;
import com.ticket.defender_core.adapter.in.web.dto.Track1CandidateLinkDto;
import com.ticket.defender_core.adapter.out.persistence.MacroAuditEvidenceReadRepository;
import com.ticket.defender_core.adapter.out.persistence.ReservationCandidateRepository;
import com.ticket.defender_core.adapter.out.persistence.ReservationImportBatchRepository;
import com.ticket.defender_core.domain.CaseSourceReference;
import com.ticket.defender_core.domain.ListingCandidateMatchRun;
import com.ticket.defender_core.domain.MacroAuditEvidence;
import com.ticket.defender_core.domain.ReservationCandidate;
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

class Track1CandidateLinkServiceTest {
    private static final String KEY = "synthetic-only-master-key-material-that-is-long-enough";

    @Test
    void linksOnlyTheCurrentBoundCandidateAndNeverReturnsReferencesOrHmac() {
        Fixture fixture = fixture(true);

        Track1CandidateLinkDto.View result = fixture.service.query(fixture.tenantId, fixture.eventId,
                List.of(fixture.graph, fixture.source), id -> Optional.of(fixture.run));

        assertThat(result.status()).isEqualTo(Track1CandidateLinkDto.Status.LINKED);
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo(Track1CandidateLinkDto.Status.LINKED);
            assertThat(item.ledgerSeats()).extracting(Track1CandidateLinkDto.LedgerSeat::status)
                    .containsExactly(Track1CandidateLinkDto.LedgerSeatStatus.FOUND);
            assertThat(item.ledgerSeats().getFirst().zone()).isEqualTo("A");
            assertThat(item.ledgerSeats().getFirst().row()).isEqualTo("1");
            assertThat(item.ledgerSeats().getFirst().seat()).isEqualTo("2");
        });
    }

    @Test
    void returnsNoTrack1LinkWhenTheCompleteBoundLedgerHasNoMemberReference() {
        Fixture fixture = fixture(false);

        Track1CandidateLinkDto.View result = fixture.service.query(fixture.tenantId, fixture.eventId,
                List.of(fixture.graph, fixture.source), id -> Optional.of(fixture.run));

        assertThat(result.status()).isEqualTo(Track1CandidateLinkDto.Status.NO_TRACK1_LINK);
        assertThat(result.items().getFirst().ledgerSeats()).extracting(Track1CandidateLinkDto.LedgerSeat::status)
                .containsExactly(Track1CandidateLinkDto.LedgerSeatStatus.NOT_IN_LEDGER);
    }

    @Test
    void returnsLedgerUnavailableInsteadOfGuessingWhenHmacKeyMaterialIsUnavailable() {
        Fixture fixture = fixture(true);
        when(fixture.gate.referenceHasherForRead()).thenReturn(Optional.empty());

        Track1CandidateLinkDto.View result = fixture.service.query(fixture.tenantId, fixture.eventId,
                List.of(fixture.graph, fixture.source), id -> Optional.of(fixture.run));

        assertThat(result.status()).isEqualTo(Track1CandidateLinkDto.Status.LEDGER_UNAVAILABLE);
        assertThat(result.items().getFirst().ledgerSeats()).extracting(Track1CandidateLinkDto.LedgerSeat::status)
                .containsExactly(Track1CandidateLinkDto.LedgerSeatStatus.LEDGER_UNAVAILABLE);
    }

    @Test
    void returnsNotApplicableWithoutBothExistingCaseSourceKinds() {
        Fixture fixture = fixture(true);

        Track1CandidateLinkDto.View result = fixture.service.query(fixture.tenantId, fixture.eventId,
                List.of(fixture.graph), id -> Optional.of(fixture.run));

        assertThat(result.status()).isEqualTo(Track1CandidateLinkDto.Status.NOT_APPLICABLE);
        assertThat(result.items()).isEmpty();
    }

    private static Fixture fixture(boolean linked) {
        UUID tenantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        UUID performanceId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        TenantPurposeReferenceHasher hasher = new TenantPurposeReferenceHasher(KEY, "v1");
        String track1Reference = "track1-reservation";
        String selectedReference = linked ? track1Reference : "other-reservation";

        MacroAuditEvidenceReadRepository clusters = mock(MacroAuditEvidenceReadRepository.class);
        ReservationCandidateRepository candidates = mock(ReservationCandidateRepository.class);
        ReservationImportBatchRepository batches = mock(ReservationImportBatchRepository.class);
        SyntheticReservationImportGate gate = mock(SyntheticReservationImportGate.class);
        CandidateRunApplicability applicability = mock(CandidateRunApplicability.class);
        Track1CandidateLinkService service = new Track1CandidateLinkService(clusters, candidates, batches, gate,
                applicability);

        CaseSourceReference graph = mock(CaseSourceReference.class);
        when(graph.getId()).thenReturn(UUID.randomUUID());
        when(graph.getSourceType()).thenReturn(CaseSourceReference.Type.STORED_CLUSTER_EVIDENCE);
        when(graph.getAnalysisRequestId()).thenReturn(UUID.randomUUID().toString());
        when(graph.getClusterId()).thenReturn("cluster-1");
        CaseSourceReference source = mock(CaseSourceReference.class);
        when(source.getId()).thenReturn(UUID.randomUUID());
        when(source.getSourceType()).thenReturn(CaseSourceReference.Type.LISTING_CANDIDATE_MATCH);
        when(source.getListingCandidateMatchRunId()).thenReturn(runId);
        when(source.getCandidateStatus()).thenReturn(CandidateMatchDecision.Status.MATCH_CANDIDATE.name());
        when(source.getCandidateId()).thenReturn(candidateId);

        ListingCandidateMatchRun run = mock(ListingCandidateMatchRun.class);
        when(run.getId()).thenReturn(runId);
        when(run.getTenantId()).thenReturn(tenantId);
        when(run.getEventId()).thenReturn(eventId);
        when(run.getStatus()).thenReturn(CandidateMatchDecision.Status.MATCH_CANDIDATE);
        when(run.getCandidateId()).thenReturn(candidateId);
        when(run.getCandidateCount()).thenReturn(1);
        when(run.getReservationBatchId()).thenReturn(batchId);
        when(run.getReservationSourceVersion()).thenReturn("ledger-sha256-fixed-v1");
        when(run.getVerifiedPerformanceId()).thenReturn(performanceId);
        when(applicability.assess(run)).thenReturn(CandidatePipelineDto.Applicability.CURRENT);

        ReservationImportBatch batch = mock(ReservationImportBatch.class);
        when(batch.getId()).thenReturn(batchId);
        when(batch.getTenantId()).thenReturn(tenantId);
        when(batch.getEventId()).thenReturn(eventId);
        when(batch.getPerformanceId()).thenReturn(performanceId);
        when(batch.getSourceVersion()).thenReturn("ledger-sha256-fixed-v1");
        when(batch.getReferenceHmacKeyVersion()).thenReturn("v1");
        when(batch.getStatus()).thenReturn(ReservationImportBatch.Status.ACCEPTED);
        when(batch.isCompleteSnapshot()).thenReturn(true);
        when(batches.findById(batchId)).thenReturn(Optional.of(batch));
        when(gate.referenceHasherForRead()).thenReturn(Optional.of(hasher));

        MacroAuditEvidence member = mock(MacroAuditEvidence.class);
        when(member.getAuditId()).thenReturn(17L);
        when(member.getReservationNo()).thenReturn(track1Reference);
        when(clusters.findMembers(eq(tenantId), eq(eventId), any(), eq("cluster-1"))).thenReturn(List.of(member));

        ReservationCandidate selected = mock(ReservationCandidate.class);
        when(selected.getId()).thenReturn(candidateId);
        when(selected.getBatchId()).thenReturn(batchId);
        when(selected.getReservationRefHmac()).thenReturn(hasher.hash(tenantId, selectedReference));
        when(selected.getZone()).thenReturn("A");
        when(selected.getRowNum()).thenReturn("1");
        when(selected.getSeatNum()).thenReturn("2");
        when(candidates.findById(candidateId)).thenReturn(Optional.of(selected));
        when(candidates.findByBatchIdAndReservationRefHmacIn(eq(batchId), any())).thenReturn(
                linked ? List.of(selected) : List.of());
        return new Fixture(service, gate, tenantId, eventId, graph, source, run);
    }

    private record Fixture(Track1CandidateLinkService service, SyntheticReservationImportGate gate, UUID tenantId,
                           UUID eventId, CaseSourceReference graph, CaseSourceReference source,
                           ListingCandidateMatchRun run) { }
}
