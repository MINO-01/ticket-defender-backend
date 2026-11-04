package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.persistence.EvidenceAssetRepository;
import com.ticket.defender_core.adapter.out.persistence.FanReportPerformanceLinkRepository;
import com.ticket.defender_core.adapter.out.persistence.FanReportRepository;
import com.ticket.defender_core.adapter.out.persistence.ListingCandidateMatchRunRepository;
import com.ticket.defender_core.adapter.out.persistence.ListingContextAnalysisRepository;
import com.ticket.defender_core.adapter.out.persistence.ListingContextObservationRepository;
import com.ticket.defender_core.adapter.out.persistence.ReservationDataSourceRepository;
import com.ticket.defender_core.adapter.out.persistence.ReservationImportBatchRepository;
import com.ticket.defender_core.adapter.out.persistence.VerifiedPerformanceRepository;
import com.ticket.defender_core.domain.EvidenceAsset;
import com.ticket.defender_core.domain.FanReport;
import com.ticket.defender_core.domain.FanReportPerformanceLink;
import com.ticket.defender_core.domain.ListingCandidateMatchRun;
import com.ticket.defender_core.domain.ListingContextAnalysis;
import com.ticket.defender_core.domain.ListingContextObservation;
import com.ticket.defender_core.domain.ReservationDataSource;
import com.ticket.defender_core.domain.ReservationImportBatch;
import com.ticket.defender_core.domain.VerifiedPerformance;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 후보 조회 I/O 밖의 상태 예약/종료를 짧은 트랜잭션으로 수행합니다. */
@Service
@RequiredArgsConstructor
public class ListingCandidateMatchWriteService {
    private final ListingCandidateMatchRunRepository runs;
    private final ListingContextAnalysisRepository analyses;
    private final ListingContextObservationRepository observations;
    private final FanReportRepository reports;
    private final EvidenceAssetRepository assets;
    private final FanReportPerformanceLinkRepository links;
    private final VerifiedPerformanceRepository performances;
    private final ReservationDataSourceRepository sources;
    private final ReservationImportBatchRepository batches;

    @Transactional
    public Started start(UUID fanReportId, UUID evidenceId, UUID analysisId, UUID membershipId,
            String idempotencyKeyDigest, String requestDigest) {
        Optional<ListingCandidateMatchRun> replay = runs
                .findByCreatedByMembershipIdAndAnalysisIdAndIdempotencyKeyDigest(membershipId, analysisId,
                        idempotencyKeyDigest);
        if (replay.isPresent()) {
            if (!replay.get().getRequestDigest().equals(requestDigest)) throw conflict("IDEMPOTENCY_KEY_REUSED");
            return new Started(replay.get(), false, null);
        }
        runs.findByActiveAnalysisId(analysisId).ifPresent(active -> {
            throw conflict("CANDIDATE_LOOKUP_IN_PROGRESS");
        });

        ListingContextAnalysis analysis = analyses.findById(analysisId).orElseThrow(ListingCandidateMatchWriteService::notFound);
        FanReport report = reports.findById(fanReportId).orElseThrow(ListingCandidateMatchWriteService::notFound);
        EvidenceAsset asset = assets.findById(evidenceId)
                .filter(item -> fanReportId.equals(item.getFanReportId()))
                .orElseThrow(ListingCandidateMatchWriteService::notFound);
        if (!analysis.getFanReportId().equals(fanReportId) || !analysis.getEvidenceId().equals(evidenceId)
                || !analysis.getTenantId().equals(report.getTenantId()) || !analysis.getEventId().equals(report.getEventId()))
            throw notFound();

        ListingCandidateMatchRun.Binding base = binding(analysis, asset, null, null, null);
        LocalDateTime now = now();
        if (analysis.getExecutionStatus() != ListingContextAnalysis.Execution.SUCCEEDED) {
            return terminal(report, analysis, asset, membershipId, idempotencyKeyDigest, requestDigest, base,
                    new CandidateMatchDecision.Result(CandidateMatchDecision.Status.ANALYSIS_FAILED,
                            CandidateMatchDecision.Reason.ANALYSIS_NOT_SUCCEEDED), now);
        }
        if (!sameEvidence(analysis, asset)) {
            return terminal(report, analysis, asset, membershipId, idempotencyKeyDigest, requestDigest, base,
                    new CandidateMatchDecision.Result(CandidateMatchDecision.Status.UNVERIFIABLE,
                            CandidateMatchDecision.Reason.ANALYSIS_OR_EVIDENCE_CHANGED), now);
        }
        FanReportPerformanceLink link = links.findByActiveFanReportId(fanReportId).orElse(null);
        if (link == null) {
            return terminal(report, analysis, asset, membershipId, idempotencyKeyDigest, requestDigest, base,
                    new CandidateMatchDecision.Result(CandidateMatchDecision.Status.UNVERIFIABLE,
                            CandidateMatchDecision.Reason.PERFORMANCE_LINK_UNVERIFIED), now);
        }
        VerifiedPerformance performance = performances.findById(link.getPerformanceId()).orElse(null);
        ListingCandidateMatchRun.Binding linked = binding(analysis, asset, link, performance, null);
        if (performance == null || !performance.getTenantId().equals(report.getTenantId())
                || !performance.getEventId().equals(report.getEventId())) {
            return terminal(report, analysis, asset, membershipId, idempotencyKeyDigest, requestDigest, linked,
                    new CandidateMatchDecision.Result(CandidateMatchDecision.Status.UNVERIFIABLE,
                            CandidateMatchDecision.Reason.PERFORMANCE_LINK_UNVERIFIED), now);
        }
        if (performance.getStatus() != VerifiedPerformance.Status.ACTIVE) {
            return terminal(report, analysis, asset, membershipId, idempotencyKeyDigest, requestDigest, linked,
                    new CandidateMatchDecision.Result(CandidateMatchDecision.Status.UNVERIFIABLE,
                            CandidateMatchDecision.Reason.PERFORMANCE_WITHDRAWN), now);
        }
        if (performance.getRevision() != link.getPerformanceRevision()) {
            return terminal(report, analysis, asset, membershipId, idempotencyKeyDigest, requestDigest, linked,
                    new CandidateMatchDecision.Result(CandidateMatchDecision.Status.UNVERIFIABLE,
                            CandidateMatchDecision.Reason.PERFORMANCE_REVISION_CHANGED), now);
        }
        ReservationDataSource source = sources.findByTenantIdAndEventIdAndPerformanceId(report.getTenantId(),
                report.getEventId(), performance.getId()).orElse(null);
        ListingCandidateMatchRun.Binding sourced = binding(analysis, asset, link, performance, source);
        if (source == null || !source.isCompleteSnapshot()) {
            return terminal(report, analysis, asset, membershipId, idempotencyKeyDigest, requestDigest, sourced,
                    new CandidateMatchDecision.Result(CandidateMatchDecision.Status.UNVERIFIABLE,
                            CandidateMatchDecision.Reason.RESERVATION_SOURCE_UNAVAILABLE), now);
        }
        ReservationImportBatch batch = batches.findById(source.getCurrentBatchId()).orElse(null);
        if (batch == null || batch.getStatus() != ReservationImportBatch.Status.ACCEPTED
                || !batch.isCompleteSnapshot() || !Objects.equals(batch.getSourceVersion(), source.getCurrentSourceVersion())) {
            return terminal(report, analysis, asset, membershipId, idempotencyKeyDigest, requestDigest, sourced,
                    new CandidateMatchDecision.Result(CandidateMatchDecision.Status.UNVERIFIABLE,
                            CandidateMatchDecision.Reason.RESERVATION_SOURCE_INCOMPLETE), now);
        }
        LookupInput lookup = seatInput(analysis);
        CandidateMatchDecision.Result seatResult = CandidateMatchDecision.decide(lookup.analysisInput(), true, true, 1);
        if (seatResult.status() != CandidateMatchDecision.Status.MATCH_CANDIDATE) {
            return terminal(report, analysis, asset, membershipId, idempotencyKeyDigest, requestDigest, sourced,
                    seatResult, now);
        }
        ListingCandidateMatchRun run = new ListingCandidateMatchRun(report.getTenantId(), report.getEventId(),
                fanReportId, evidenceId, analysisId, membershipId, idempotencyKeyDigest, requestDigest, sourced, now);
        return new Started(runs.saveAndFlush(run), true, lookup);
    }

    @Transactional
    public ListingCandidateMatchRun complete(UUID runId, CandidateMatchDecision.Result result, Integer candidateCount,
            UUID candidateId) {
        ListingCandidateMatchRun run = runs.findById(runId).orElseThrow(ListingCandidateMatchWriteService::notFound);
        if (!run.inProgress()) return run;
        run.complete(result, candidateCount, candidateId, now());
        return runs.saveAndFlush(run);
    }

    @Transactional
    public ListingCandidateMatchRun lookupFailed(UUID runId) {
        return complete(runId, new CandidateMatchDecision.Result(CandidateMatchDecision.Status.LOOKUP_FAILED,
                CandidateMatchDecision.Reason.RESERVATION_LOOKUP_FAILED), null, null);
    }

    @Transactional(readOnly = true)
    public Optional<ListingCandidateMatchRun> replay(UUID membershipId, UUID analysisId, String keyDigest) {
        return runs.findByCreatedByMembershipIdAndAnalysisIdAndIdempotencyKeyDigest(membershipId, analysisId, keyDigest);
    }

    @Transactional(readOnly = true)
    public ListingCandidateMatchRun current(UUID runId) {
        return runs.findById(runId).orElseThrow(ListingCandidateMatchWriteService::notFound);
    }

    private Started terminal(FanReport report, ListingContextAnalysis analysis, EvidenceAsset asset, UUID membershipId,
            String keyDigest, String requestDigest, ListingCandidateMatchRun.Binding binding,
            CandidateMatchDecision.Result result, LocalDateTime now) {
        ListingCandidateMatchRun run = ListingCandidateMatchRun.terminal(report.getTenantId(), report.getEventId(),
                report.getFanReportId(), asset.getEvidenceId(), analysis.getId(), membershipId, keyDigest,
                requestDigest, binding, result, now);
        return new Started(runs.saveAndFlush(run), true, null);
    }

    private LookupInput seatInput(ListingContextAnalysis analysis) {
        Map<ListingContextObservation.Field, ListingContextObservation> byField = new EnumMap<>(
                ListingContextObservation.Field.class);
        for (ListingContextObservation observation : observations.findByAnalysisIdIn(List.of(analysis.getId())))
            byField.put(observation.getFieldName(), observation);
        List<CandidateMatchDecision.SeatObservation> seatObservations = List.of(
                observation(byField.get(ListingContextObservation.Field.zone)),
                observation(byField.get(ListingContextObservation.Field.row)),
                observation(byField.get(ListingContextObservation.Field.seat)));
        CandidateMatchDecision.AnalysisInput input = CandidateMatchDecision.AnalysisInput.succeeded(seatObservations);
        try {
            return new LookupInput(input, CandidatePipelineSupport.seat(seatObservations.get(0).value()),
                    CandidatePipelineSupport.seat(seatObservations.get(1).value()),
                    CandidatePipelineSupport.seat(seatObservations.get(2).value()));
        } catch (RuntimeException invalid) {
            return new LookupInput(input, null, null, null);
        }
    }

    private static CandidateMatchDecision.SeatObservation observation(ListingContextObservation item) {
        if (item == null) return new CandidateMatchDecision.SeatObservation(CandidateMatchDecision.ObservationState.UNKNOWN, null);
        CandidateMatchDecision.ObservationState state = switch (item.getState()) {
            case FOUND -> CandidateMatchDecision.ObservationState.FOUND;
            case UNKNOWN -> CandidateMatchDecision.ObservationState.UNKNOWN;
            case AMBIGUOUS -> CandidateMatchDecision.ObservationState.AMBIGUOUS;
        };
        return new CandidateMatchDecision.SeatObservation(state, item.getValueText());
    }

    private static boolean sameEvidence(ListingContextAnalysis analysis, EvidenceAsset asset) {
        return Objects.equals(analysis.getExpectedSha256(), analysis.getVerifiedSha256())
                && Objects.equals(analysis.getExpectedSha256(), asset.getSha256())
                && Objects.equals(analysis.getObjectMarker(), asset.getObjectVersion());
    }

    private static ListingCandidateMatchRun.Binding binding(ListingContextAnalysis analysis, EvidenceAsset asset,
            FanReportPerformanceLink link, VerifiedPerformance performance, ReservationDataSource source) {
        return new ListingCandidateMatchRun.Binding(link == null ? null : link.getId(),
                link == null ? null : link.getLinkRevision(), performance == null ? null : performance.getId(),
                performance == null ? null : performance.getRevision(), source == null ? null : source.getId(),
                source == null ? null : source.getSourceRevision(), source == null ? null : source.getCurrentBatchId(),
                source == null ? null : source.getCurrentSourceVersion(), asset.getSha256(),
                analysis.getResponseSha256(), analysis.getRowVersion());
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static CaseApiException conflict(String code) {
        return new CaseApiException(HttpStatus.CONFLICT, code);
    }

    private static CaseApiException notFound() {
        return new CaseApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }

    public record Started(ListingCandidateMatchRun run, boolean created, LookupInput lookup) { }
    public record LookupInput(CandidateMatchDecision.AnalysisInput analysisInput, String zone, String row, String seat) { }
}
