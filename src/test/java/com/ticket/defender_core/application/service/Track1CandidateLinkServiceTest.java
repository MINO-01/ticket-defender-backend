package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.CandidatePipelineDto;
import com.ticket.defender_core.adapter.in.web.dto.Track1CandidateLinkDto;
import com.ticket.defender_core.adapter.out.persistence.MacroAuditEvidenceReadRepository;
import com.ticket.defender_core.domain.CaseSourceReference;
import com.ticket.defender_core.domain.ListingCandidateMatchRun;
import com.ticket.defender_core.domain.MacroAuditEvidence;
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
    @Test
    void linksOnlyTheCurrentBoundCandidateAndNeverReturnsReferencesOrHmac() {
        Fixture fixture = fixture(true);

        Track1CandidateLinkDto.View result = fixture.service.query(fixture.tenantId, fixture.eventId,
                List.of(fixture.graph, fixture.source), id -> Optional.of(fixture.run));

        assertThat(result.status()).isEqualTo(Track1CandidateLinkDto.Status.LINKED);
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo(Track1CandidateLinkDto.Status.LINKED);
            assertThat(item.linkBasis()).isEqualTo(Track1CandidateLinkDto.LinkBasis.RESERVATION_REFERENCE);
            assertThat(item.ledgerSeats()).extracting(Track1CandidateLinkDto.LedgerSeat::status)
                    .containsExactly(Track1CandidateLinkDto.LedgerSeatStatus.FOUND);
            assertThat(item.ledgerSeats().getFirst().zone()).isEqualTo("A");
            assertThat(item.ledgerSeats().getFirst().row()).isEqualTo("1");
            assertThat(item.ledgerSeats().getFirst().seat()).isEqualTo("2");
        });
    }

    @Test
    void linkedMeansReservationReferenceConvergenceEvenWhenTheLedgerMemberHasTwoSeats() {
        Fixture fixture = fixture(true);
        when(fixture.ledgerSeats.lookup(any(), any(), any(), any(), any(), any(), any())).thenReturn(
                new Track1LedgerSeatLookupService.Result(true, true, List.of(new Track1LedgerSeatLookupService.LedgerSeat(17L,
                        Track1LedgerSeatLookupService.Status.FOUND, null, null, null,
                        List.of(new Track1LedgerSeatLookupService.Seat("A", "1", "2"),
                                new Track1LedgerSeatLookupService.Seat("A", "1", "3"))))));

        Track1CandidateLinkDto.View result = fixture.service.query(fixture.tenantId, fixture.eventId,
                List.of(fixture.graph, fixture.source), id -> Optional.of(fixture.run));

        assertThat(result.status()).isEqualTo(Track1CandidateLinkDto.Status.LINKED);
        assertThat(result.items().getFirst().linkBasis()).isEqualTo(Track1CandidateLinkDto.LinkBasis.RESERVATION_REFERENCE);
        assertThat(result.items().getFirst().ledgerSeats().getFirst().seats()).hasSize(2);
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
        when(fixture.ledgerSeats.lookup(any(), any(), any(), any(), any(), any(), any())).thenReturn(
                new Track1LedgerSeatLookupService.Result(false, false, List.of(
                        new Track1LedgerSeatLookupService.LedgerSeat(17L,
                                Track1LedgerSeatLookupService.Status.LEDGER_UNAVAILABLE, null, null, null))));

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
        MacroAuditEvidenceReadRepository clusters = mock(MacroAuditEvidenceReadRepository.class);
        Track1LedgerSeatLookupService ledgerSeats = mock(Track1LedgerSeatLookupService.class);
        CandidateRunApplicability applicability = mock(CandidateRunApplicability.class);
        Track1CandidateLinkService service = new Track1CandidateLinkService(clusters, ledgerSeats, applicability);

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

        MacroAuditEvidence member = mock(MacroAuditEvidence.class);
        when(member.getAuditId()).thenReturn(17L);
        when(clusters.findMembers(eq(tenantId), eq(eventId), any(), eq("cluster-1"))).thenReturn(List.of(member));

        when(ledgerSeats.lookup(eq(tenantId), eq(eventId), eq(performanceId), eq("ledger-sha256-fixed-v1"),
                eq(batchId), eq(candidateId), any())).thenReturn(new Track1LedgerSeatLookupService.Result(
                true, linked, List.of(new Track1LedgerSeatLookupService.LedgerSeat(17L,
                linked ? Track1LedgerSeatLookupService.Status.FOUND : Track1LedgerSeatLookupService.Status.NOT_IN_LEDGER,
                linked ? "A" : null, linked ? "1" : null, linked ? "2" : null))));
        return new Fixture(service, ledgerSeats, tenantId, eventId, graph, source, run);
    }

    private record Fixture(Track1CandidateLinkService service, Track1LedgerSeatLookupService ledgerSeats, UUID tenantId,
                           UUID eventId, CaseSourceReference graph, CaseSourceReference source,
                           ListingCandidateMatchRun run) { }
}
