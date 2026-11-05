package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.CandidatePipelineDto;
import com.ticket.defender_core.adapter.out.persistence.EvidenceAssetRepository;
import com.ticket.defender_core.adapter.out.persistence.FanReportPerformanceLinkRepository;
import com.ticket.defender_core.adapter.out.persistence.ListingContextAnalysisRepository;
import com.ticket.defender_core.adapter.out.persistence.ReservationDataSourceRepository;
import com.ticket.defender_core.adapter.out.persistence.VerifiedPerformanceRepository;
import com.ticket.defender_core.domain.EvidenceAsset;
import com.ticket.defender_core.domain.FanReportPerformanceLink;
import com.ticket.defender_core.domain.ListingCandidateMatchRun;
import com.ticket.defender_core.domain.ListingContextAnalysis;
import com.ticket.defender_core.domain.ReservationDataSource;
import com.ticket.defender_core.domain.VerifiedPerformance;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Computes present applicability without mutating the immutable candidate execution. */
@Component
@RequiredArgsConstructor
public class CandidateRunApplicability {
    private final FanReportPerformanceLinkRepository links;
    private final VerifiedPerformanceRepository performances;
    private final ReservationDataSourceRepository sources;
    private final ListingContextAnalysisRepository analyses;
    private final EvidenceAssetRepository assets;

    public CandidatePipelineDto.Applicability assess(ListingCandidateMatchRun run) {
        if (run.getPerformanceLinkId() == null || run.getVerifiedPerformanceId() == null)
            return CandidatePipelineDto.Applicability.NOT_APPLICABLE;
        FanReportPerformanceLink currentLink = links.findByActiveFanReportId(run.getFanReportId()).orElse(null);
        if (currentLink == null || !currentLink.getId().equals(run.getPerformanceLinkId())
                || currentLink.getLinkRevision() != run.getPerformanceLinkRevision())
            return CandidatePipelineDto.Applicability.PERFORMANCE_LINK_REPLACED_OR_WITHDRAWN;
        VerifiedPerformance performance = performances.findById(run.getVerifiedPerformanceId()).orElse(null);
        if (performance == null || performance.getStatus() != VerifiedPerformance.Status.ACTIVE)
            return CandidatePipelineDto.Applicability.PERFORMANCE_WITHDRAWN;
        if (performance.getRevision() != run.getVerifiedPerformanceRevision())
            return CandidatePipelineDto.Applicability.PERFORMANCE_REVISION_CHANGED;
        ReservationDataSource source = sources.findByTenantIdAndEventIdAndPerformanceId(run.getTenantId(),
                run.getEventId(), performance.getId()).orElse(null);
        if (source == null || !source.isCompleteSnapshot() || !Objects.equals(source.getId(), run.getReservationSourceId())
                || source.getSourceRevision() != run.getReservationSourceRevision()
                || !Objects.equals(source.getCurrentBatchId(), run.getReservationBatchId())
                || !Objects.equals(source.getCurrentSourceVersion(), run.getReservationSourceVersion()))
            return CandidatePipelineDto.Applicability.RESERVATION_SOURCE_SUPERSEDED;
        ListingContextAnalysis analysis = analyses.findById(run.getAnalysisId()).orElse(null);
        EvidenceAsset asset = assets.findById(run.getEvidenceId()).orElse(null);
        if (analysis == null || asset == null || analysis.getRowVersion() != value(run.getAnalysisRowVersion())
                || !Objects.equals(analysis.getResponseSha256(), run.getAnalysisResponseSha256())
                || !Objects.equals(asset.getSha256(), run.getEvidenceSha256())
                || !Objects.equals(analysis.getExpectedSha256(), run.getEvidenceSha256())
                || !Objects.equals(analysis.getVerifiedSha256(), run.getEvidenceSha256()))
            return CandidatePipelineDto.Applicability.ANALYSIS_OR_EVIDENCE_CHANGED;
        return CandidatePipelineDto.Applicability.CURRENT;
    }

    private static long value(Long source) {
        return source == null ? Long.MIN_VALUE : source;
    }
}
