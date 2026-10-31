package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.api.ListingContextClient;
import com.ticket.defender_core.adapter.out.persistence.EvidenceAssetRepository;
import com.ticket.defender_core.adapter.out.persistence.FanReportRepository;
import com.ticket.defender_core.adapter.out.persistence.ListingContextAnalysisRepository;
import com.ticket.defender_core.adapter.out.persistence.ListingContextObservationRepository;
import com.ticket.defender_core.domain.EvidenceAsset;
import com.ticket.defender_core.domain.FanReport;
import com.ticket.defender_core.domain.ListingContextAnalysis;
import com.ticket.defender_core.domain.ListingContextObservation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Listing Context 분석 이력의 짧은 DB 트랜잭션만 담당합니다. 저장소 읽기와 Python 호출은 이 트랜잭션 밖에서 수행합니다. */
@Service
@RequiredArgsConstructor
public class ListingContextAnalysisWriteService {
    private static final Set<FanReport.IntakeStatus> ANALYZABLE =
            EnumSet.of(FanReport.IntakeStatus.RECEIVED, FanReport.IntakeStatus.ANALYSIS_PENDING);
    private static final Set<String> MEDIA_TYPES = Set.of("image/jpeg", "image/png");

    private final ListingContextAnalysisRepository analyses;
    private final ListingContextObservationRepository observations;
    private final FanReportRepository reports;
    private final EvidenceAssetRepository assets;

    /**
     * 같은 요청자의 같은 Idempotency-Key 이력이 있으면 그 행을 돌려주고, 없으면 활성 슬롯을 점유한 새 시도를 만듭니다.
     * 임대 시간이 지난 IN_PROGRESS 행은 ABANDONED로 닫아 슬롯을 해제합니다. 사전 검사 실패는 Python 호출 없이 즉시 종료합니다.
     */
    @Transactional
    public Started start(UUID fanReportId, UUID evidenceId, UUID membershipId, String keyDigest,
            LocalDateTime leaseExpiresAt) {
        LocalDateTime now = now();
        Optional<ListingContextAnalysis> replay = analyses
                .findByCreatedByMembershipIdAndEvidenceIdAndIdempotencyKeyDigest(membershipId, evidenceId, keyDigest);
        if (replay.isPresent()) return new Started(abandonIfExpired(replay.get(), now), false);
        analyses.findByActiveEvidenceId(evidenceId).ifPresent(active -> {
            if (!active.leaseExpired(now)) throw new CaseApiException(HttpStatus.CONFLICT, "ANALYSIS_IN_PROGRESS");
            abandonIfExpired(active, now);
            analyses.flush();
        });
        FanReport report = reports.findById(fanReportId).orElseThrow(ListingContextAnalysisWriteService::notFound);
        EvidenceAsset asset = assets.findById(evidenceId)
                .filter(found -> fanReportId.equals(found.getFanReportId()))
                .orElseThrow(ListingContextAnalysisWriteService::notFound);
        ListingContextAnalysis analysis = new ListingContextAnalysis(UUID.randomUUID(), report.getTenantId(),
                report.getEventId(), report.getFanReportId(), asset.getEvidenceId(), membershipId, keyDigest,
                UUID.randomUUID(), CaseSnapshotManifest.sha256(asset.getObjectKey()), asset.getObjectVersion(),
                asset.getSha256(), asset.getByteSize(), asset.getMediaType(), now, leaseExpiresAt);
        String rejected = precheck(report, asset, now);
        if (rejected != null) analysis.fail(ListingContextAnalysis.Execution.INPUT_REJECTED, rejected, null, null, now);
        return new Started(analyses.saveAndFlush(analysis), true);
    }

    @Transactional(readOnly = true)
    public Optional<ListingContextAnalysis> replay(UUID membershipId, UUID evidenceId, String keyDigest) {
        return analyses.findByCreatedByMembershipIdAndEvidenceIdAndIdempotencyKeyDigest(
                membershipId, evidenceId, keyDigest);
    }

    /** 원본 읽기나 입력 검증, Python 호출 실패를 FAILED로 기록하고 활성 슬롯을 해제합니다. */
    @Transactional
    public ListingContextAnalysis fail(UUID analysisId, ListingContextAnalysis.Execution stage, String errorCode,
            Integer analyzerHttpStatus, String analyzerErrorCode, String verifiedSha256, Long verifiedByteSize) {
        ListingContextAnalysis analysis = open(analysisId);
        if (!analysis.inProgress()) return analysis;
        if (verifiedSha256 != null) analysis.recordVerifiedInput(verifiedSha256, verifiedByteSize);
        analysis.fail(stage, errorCode, analyzerHttpStatus, analyzerErrorCode, now());
        return analyses.saveAndFlush(analysis);
    }

    /** 계약 검증을 통과한 응답만 필드 관찰과 함께 저장합니다. 이미 닫힌 시도에는 늦게 도착한 결과를 쓰지 않습니다. */
    @Transactional
    public ListingContextAnalysis succeed(UUID analysisId, String verifiedSha256, long verifiedByteSize,
            ListingContextClient.Success result) {
        ListingContextAnalysis analysis = open(analysisId);
        if (!analysis.inProgress()) return analysis;
        ListingContextClient.Parsed parsed = result.parsed();
        analysis.recordVerifiedInput(verifiedSha256, verifiedByteSize);
        FanReport report = reports.findById(analysis.getFanReportId())
                .orElseThrow(ListingContextAnalysisWriteService::notFound);
        analysis.succeed(parsed.status(), parsed.modelName(), parsed.modelVersion(),
                LocalDateTime.ofInstant(parsed.analyzedAt(), ZoneOffset.UTC), result.responseSha256(),
                candidateMatchReason(report, parsed), now());
        ListingContextAnalysis saved = analyses.saveAndFlush(analysis);
        for (ListingContextClient.Observation item : parsed.observations())
            observations.save(new ListingContextObservation(saved.getId(), item.field(), item.state(), item.value(),
                    item.priceRaw(), item.priceAmount(), item.evidenceQuote(), item.unknownReason(),
                    item.candidates()));
        observations.flush();
        return saved;
    }

    @Transactional(readOnly = true)
    public ListingContextAnalysis current(UUID analysisId) {
        return analyses.findById(analysisId).orElseThrow(ListingContextAnalysisWriteService::notFound);
    }

    /**
     * 기존 예매 후보 대조는 검증된 회차 식별자와 좌석 3요소, 일치 신뢰도가 모두 필요합니다.
     * Listing Context 응답은 일치 신뢰도를 제공하지 않으므로 추출 신뢰도로 대체하지 않고 연결하지 않은 사유만 남깁니다.
     */
    private static String candidateMatchReason(FanReport report, ListingContextClient.Parsed parsed) {
        if (report.getVerifiedPerformanceRef() == null || report.getVerifiedPerformanceRef().isBlank())
            return "PERFORMANCE_REF_UNVERIFIED";
        boolean seatFound = parsed.observations().stream()
                .filter(item -> item.field() == ListingContextObservation.Field.zone
                        || item.field() == ListingContextObservation.Field.row
                        || item.field() == ListingContextObservation.Field.seat)
                .allMatch(item -> item.state() == ListingContextObservation.State.FOUND);
        return seatFound ? "MATCH_SCORE_UNAVAILABLE" : "SEAT_NOT_FOUND";
    }

    private static String precheck(FanReport report, EvidenceAsset asset, LocalDateTime now) {
        if (!ANALYZABLE.contains(report.getIntakeStatus())
                || asset.getStorageStatus() != EvidenceAsset.StorageStatus.STORED
                || asset.getObjectVersion() == null || asset.getObjectVersion().isBlank())
            return "SOURCE_NOT_AVAILABLE";
        if (!report.getRetentionUntil().isAfter(now) || !asset.getRetentionUntil().isAfter(now))
            return "RETENTION_EXPIRED";
        if (!MEDIA_TYPES.contains(asset.getMediaType())) return "UNSUPPORTED_IMAGE_TYPE";
        if (asset.getByteSize() < 1 || asset.getByteSize() > ListingContextClient.MAX_IMAGE_BYTES)
            return "ANALYSIS_INPUT_TOO_LARGE";
        return null;
    }

    private ListingContextAnalysis abandonIfExpired(ListingContextAnalysis analysis, LocalDateTime now) {
        if (!analysis.leaseExpired(now)) return analysis;
        analysis.fail(ListingContextAnalysis.Execution.ABANDONED, "ANALYSIS_ABANDONED", null, null, now);
        return analyses.save(analysis);
    }

    private ListingContextAnalysis open(UUID analysisId) {
        return analyses.findById(analysisId).orElseThrow(ListingContextAnalysisWriteService::notFound);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    /** created가 false이면 같은 Idempotency-Key의 기존 시도이며 Python을 다시 호출하지 않습니다. */
    public record Started(ListingContextAnalysis analysis, boolean created) { }

    private static CaseApiException notFound() {
        return new CaseApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }
}
