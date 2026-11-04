package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.CandidatePipelineDto;
import com.ticket.defender_core.adapter.out.persistence.EvidenceAssetRepository;
import com.ticket.defender_core.adapter.out.persistence.FanReportPerformanceLinkRepository;
import com.ticket.defender_core.adapter.out.persistence.ListingCandidateMatchRunRepository;
import com.ticket.defender_core.adapter.out.persistence.ListingContextAnalysisRepository;
import com.ticket.defender_core.adapter.out.persistence.ReservationCandidateRepository;
import com.ticket.defender_core.adapter.out.persistence.ReservationDataSourceRepository;
import com.ticket.defender_core.adapter.out.persistence.VerifiedPerformanceRepository;
import com.ticket.defender_core.domain.EvidenceAsset;
import com.ticket.defender_core.domain.FanReport;
import com.ticket.defender_core.domain.ListingCandidateMatchRun;
import com.ticket.defender_core.domain.ListingContextAnalysis;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ListingCandidateMatchServiceTest {
    private final UUID tenantId = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID eventId = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID memberId = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private final UUID reportId = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private final UUID evidenceId = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private final UUID analysisId = UUID.fromString("66666666-6666-4666-8666-666666666666");

    private CandidatePipelineAccess access;
    private ListingCandidateMatchWriteService writer;
    private ReservationCandidateRepository candidates;
    private ListingCandidateMatchService service;
    private ListingCandidateMatchRun inProgress;

    @BeforeEach
    void setup() {
        access = mock(CandidatePipelineAccess.class);
        writer = mock(ListingCandidateMatchWriteService.class);
        ListingCandidateMatchRunRepository runs = mock(ListingCandidateMatchRunRepository.class);
        ListingContextAnalysisRepository analyses = mock(ListingContextAnalysisRepository.class);
        EvidenceAssetRepository assets = mock(EvidenceAssetRepository.class);
        candidates = mock(ReservationCandidateRepository.class);
        FanReportPerformanceLinkRepository links = mock(FanReportPerformanceLinkRepository.class);
        VerifiedPerformanceRepository performances = mock(VerifiedPerformanceRepository.class);
        ReservationDataSourceRepository sources = mock(ReservationDataSourceRepository.class);
        service = new ListingCandidateMatchService(access, writer, runs, analyses, assets, candidates, links,
                performances, sources);

        FanReport report = mock(FanReport.class);
        when(report.getFanReportId()).thenReturn(reportId);
        when(access.report(eq(reportId), any())).thenReturn(
                new CandidatePipelineAccess.Scope(tenantId, eventId, memberId, report));
        ListingContextAnalysis analysis = mock(ListingContextAnalysis.class);
        when(analysis.getFanReportId()).thenReturn(reportId);
        when(analysis.getEvidenceId()).thenReturn(evidenceId);
        when(analysis.getTenantId()).thenReturn(tenantId);
        when(analysis.getEventId()).thenReturn(eventId);
        when(analyses.findById(analysisId)).thenReturn(Optional.of(analysis));
        EvidenceAsset asset = mock(EvidenceAsset.class);
        when(asset.getFanReportId()).thenReturn(reportId);
        when(assets.findById(evidenceId)).thenReturn(Optional.of(asset));

        ListingCandidateMatchRun.Binding binding = new ListingCandidateMatchRun.Binding(null, null, null, null,
                UUID.randomUUID(), 1, UUID.randomUUID(), "synthetic-v1", "a".repeat(64), "b".repeat(64), 0L);
        inProgress = new ListingCandidateMatchRun(tenantId, eventId, reportId, evidenceId, analysisId, memberId,
                "c".repeat(64), "d".repeat(64), binding, LocalDateTime.parse("2026-11-04T12:00:00"));
        CandidateMatchDecision.AnalysisInput input = CandidateMatchDecision.AnalysisInput.succeeded(List.of(
                new CandidateMatchDecision.SeatObservation(CandidateMatchDecision.ObservationState.FOUND, "VIP"),
                new CandidateMatchDecision.SeatObservation(CandidateMatchDecision.ObservationState.FOUND, "10"),
                new CandidateMatchDecision.SeatObservation(CandidateMatchDecision.ObservationState.FOUND, "5")));
        when(writer.start(eq(reportId), eq(evidenceId), eq(analysisId), eq(memberId), any(), any())).thenReturn(
                new ListingCandidateMatchWriteService.Started(inProgress, true,
                        new ListingCandidateMatchWriteService.LookupInput(input, "VIP", "10", "5")));
        when(candidates.findTop2ByBatchIdAndZoneAndRowNumAndSeatNum(eq(inProgress.getReservationBatchId()),
                eq("VIP"), eq("10"), eq("5"))).thenThrow(
                new DataAccessResourceFailureException("synthetic candidate lookup outage"));
    }

    @Test
    void recordsLookupFailureWhenTheDatabaseRecoversEnoughToCloseTheRun() {
        ListingCandidateMatchRun failed = ListingCandidateMatchRun.terminal(tenantId, eventId, reportId, evidenceId,
                analysisId, memberId, "c".repeat(64), "d".repeat(64), null,
                new CandidateMatchDecision.Result(CandidateMatchDecision.Status.LOOKUP_FAILED,
                        CandidateMatchDecision.Reason.RESERVATION_LOOKUP_FAILED),
                LocalDateTime.parse("2026-11-04T12:00:01"));
        when(writer.lookupFailed(inProgress.getId())).thenReturn(failed);

        CandidatePipelineDto.CandidateRun result = service.match(reportId, evidenceId, analysisId,
                UUID.randomUUID().toString());

        assertThat(result.status()).isEqualTo(CandidateMatchDecision.Status.LOOKUP_FAILED);
        assertThat(result.reason()).isEqualTo(CandidateMatchDecision.Reason.RESERVATION_LOOKUP_FAILED);
        assertThat(result.candidateCount()).isNull();
        verify(writer).lookupFailed(inProgress.getId());
    }

    @Test
    void reportsUnrecordedFailureWhenTheSameDatabaseCannotPersistTheAuditRow() {
        when(writer.lookupFailed(inProgress.getId())).thenThrow(
                new DataAccessResourceFailureException("synthetic total database outage"));

        assertThatThrownBy(() -> service.match(reportId, evidenceId, analysisId, UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(CaseApiException.class, error -> {
                    assertThat(error.status().value()).isEqualTo(503);
                    assertThat(error.code()).isEqualTo("CANDIDATE_LOOKUP_FAILED_UNRECORDED");
                });
    }

    @Test
    void reportsUnrecordedFailureWhenTheDatabaseCannotCreateTheInitialAuditRun() {
        when(writer.start(eq(reportId), eq(evidenceId), eq(analysisId), eq(memberId), any(), any())).thenThrow(
                new CannotCreateTransactionException("synthetic total database outage before run creation"));

        assertThatThrownBy(() -> service.match(reportId, evidenceId, analysisId, UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(CaseApiException.class, error -> {
                    assertThat(error.status().value()).isEqualTo(503);
                    assertThat(error.code()).isEqualTo("CANDIDATE_LOOKUP_FAILED_UNRECORDED");
                });
        verifyNoInteractions(candidates);
    }
}
