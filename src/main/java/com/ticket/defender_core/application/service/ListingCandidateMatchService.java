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
import com.ticket.defender_core.domain.FanReportPerformanceLink;
import com.ticket.defender_core.domain.ListingCandidateMatchRun;
import com.ticket.defender_core.domain.ListingContextAnalysis;
import com.ticket.defender_core.domain.ReservationCandidate;
import com.ticket.defender_core.domain.ReservationDataSource;
import com.ticket.defender_core.domain.VerifiedPerformance;
import com.ticket.defender_core.security.AdminRole;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 저장된 Listing Context 관찰값을 현재 검증 회차와 완전 예매 스냅샷에만 대조합니다. */
@Service
@RequiredArgsConstructor
public class ListingCandidateMatchService {
    private static final Set<AdminRole> RUN_ROLES = Set.of(AdminRole.ANALYST, AdminRole.REVIEWER);

    private final CandidatePipelineAccess access;
    private final ListingCandidateMatchWriteService writer;
    private final ListingCandidateMatchRunRepository runs;
    private final ListingContextAnalysisRepository analyses;
    private final EvidenceAssetRepository assets;
    private final ReservationCandidateRepository candidates;
    private final FanReportPerformanceLinkRepository links;
    private final VerifiedPerformanceRepository performances;
    private final ReservationDataSourceRepository sources;

    public CandidatePipelineDto.CandidateRun match(UUID fanReportId, UUID evidenceId, UUID analysisId,
            String idempotencyKey) {
        CandidatePipelineAccess.Scope scope = access.report(fanReportId, RUN_ROLES);
        route(scope, evidenceId, analysisId);
        String keyDigest = CandidatePipelineSupport.idempotencyDigest(idempotencyKey);
        String requestDigest = CandidatePipelineSupport.sha256("CANDIDATE_MATCH", analysisId.toString());
        ListingCandidateMatchWriteService.Started started;
        try {
            started = writer.start(fanReportId, evidenceId, analysisId, scope.membershipId(), keyDigest, requestDigest);
        } catch (DataIntegrityViolationException | OptimisticLockingFailureException collision) {
            try {
                started = new ListingCandidateMatchWriteService.Started(writer.replay(scope.membershipId(), analysisId, keyDigest)
                        .orElseThrow(() -> conflict("CANDIDATE_LOOKUP_IN_PROGRESS")), false, null);
            } catch (DataAccessException | CannotCreateTransactionException unavailable) {
                throw unrecordedFailure();
            }
        } catch (DataAccessException | CannotCreateTransactionException unavailable) {
            // If the initial audit row cannot be created, the same database cannot guarantee a failure row either.
            throw unrecordedFailure();
        }
        if (!started.created()) {
            if (started.run().inProgress()) throw conflict("CANDIDATE_LOOKUP_IN_PROGRESS");
            return view(started.run());
        }
        if (!started.run().inProgress()) return view(started.run());
        try {
            ListingCandidateMatchWriteService.LookupInput lookup = started.lookup();
            if (lookup == null || lookup.zone() == null || lookup.row() == null || lookup.seat() == null)
                throw new IllegalStateException("Validated run is missing lookup input");
            List<ReservationCandidate> found = candidates.findTop2ByBatchIdAndZoneAndRowNumAndSeatNum(
                    started.run().getReservationBatchId(), lookup.zone(), lookup.row(), lookup.seat());
            CandidateMatchDecision.Result result = CandidateMatchDecision.decide(lookup.analysisInput(), true, true,
                    found.size());
            ListingCandidateMatchRun completed = writer.complete(started.run().getId(), result, found.size(),
                    found.size() == 1 ? found.getFirst().getId() : null);
            return view(completed);
        } catch (DataAccessException | CannotCreateTransactionException lookupFailure) {
            try {
                return view(writer.lookupFailed(started.run().getId()));
            } catch (DataAccessException | CannotCreateTransactionException auditUnavailable) {
                // A total database failure cannot also be recorded in that same database.
                throw unrecordedFailure();
            }
        }
    }

    public CandidatePipelineDto.CandidateRunPage history(UUID fanReportId, UUID evidenceId, UUID analysisId,
            int page, int pageSize) {
        if (page < 0 || pageSize < 1 || pageSize > 100 || (long) page * pageSize > Integer.MAX_VALUE)
            throw new CaseApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        CandidatePipelineAccess.Scope scope = access.report(fanReportId, RUN_ROLES);
        route(scope, evidenceId, analysisId);
        var found = runs.findByAnalysisId(analysisId, PageRequest.of(page, pageSize,
                Sort.by("createdAt").descending().and(Sort.by("id").descending())));
        return new CandidatePipelineDto.CandidateRunPage(found.stream().map(this::view).toList(), page, pageSize,
                found.hasNext());
    }

    private void route(CandidatePipelineAccess.Scope scope, UUID evidenceId, UUID analysisId) {
        ListingContextAnalysis analysis = analyses.findById(analysisId).orElseThrow(ListingCandidateMatchService::notFound);
        EvidenceAsset asset = assets.findById(evidenceId).orElseThrow(ListingCandidateMatchService::notFound);
        if (!analysis.getFanReportId().equals(scope.report().getFanReportId())
                || !analysis.getEvidenceId().equals(evidenceId) || !asset.getFanReportId().equals(scope.report().getFanReportId())
                || !analysis.getTenantId().equals(scope.tenantId()) || !analysis.getEventId().equals(scope.eventId()))
            throw notFound();
    }

    private CandidatePipelineDto.CandidateRun view(ListingCandidateMatchRun run) {
        return new CandidatePipelineDto.CandidateRun(run.getId(), run.getAnalysisId(), run.getFanReportId(),
                run.getEvidenceId(), run.getStatus(), run.getReason(), run.getCandidateCount(), run.getCandidateId(),
                run.getPerformanceLinkId(), run.getPerformanceLinkRevision(), run.getVerifiedPerformanceId(),
                run.getVerifiedPerformanceRevision(), run.getReservationBatchId(), run.getReservationSourceVersion(),
                run.getEvidenceSha256(), run.getAnalysisResponseSha256(), run.getAnalysisRowVersion(),
                applicability(run), run.getCreatedAt(), run.getCompletedAt());
    }

    private CandidatePipelineDto.Applicability applicability(ListingCandidateMatchRun run) {
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

    private static CaseApiException conflict(String code) {
        return new CaseApiException(HttpStatus.CONFLICT, code);
    }

    private static CaseApiException unrecordedFailure() {
        return new CaseApiException(HttpStatus.SERVICE_UNAVAILABLE, "CANDIDATE_LOOKUP_FAILED_UNRECORDED");
    }

    private static CaseApiException notFound() {
        return new CaseApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }
}
