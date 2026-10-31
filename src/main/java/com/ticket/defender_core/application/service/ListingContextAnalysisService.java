package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.ListingContextAnalysisDto;
import com.ticket.defender_core.adapter.out.api.ListingContextClient;
import com.ticket.defender_core.adapter.out.persistence.EvidenceAssetRepository;
import com.ticket.defender_core.adapter.out.persistence.FanReportRepository;
import com.ticket.defender_core.adapter.out.persistence.ListingContextAnalysisRepository;
import com.ticket.defender_core.adapter.out.persistence.ListingContextObservationRepository;
import com.ticket.defender_core.adapter.out.storage.S3EvidenceObjectStorage;
import com.ticket.defender_core.domain.EvidenceAsset;
import com.ticket.defender_core.domain.FanReport;
import com.ticket.defender_core.domain.ListingContextAnalysis;
import com.ticket.defender_core.domain.ListingContextObservation;
import com.ticket.defender_core.security.AdminAccessService;
import com.ticket.defender_core.security.AdminResourceScope;
import com.ticket.defender_core.security.AdminRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 저장된 팬 제보 이미지를 서버에서 검증한 뒤 Python Listing Context API로 전달하고 결과를 기록합니다.
 * 분석 결과는 조사 후보이며 Case 확정, 예매 취소, 제재, PDF 발급으로 이어지지 않습니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ListingContextAnalysisService {
    private static final Pattern KEY = Pattern.compile(
            "(?i)[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    private static final Set<AdminRole> ROLES = Set.of(AdminRole.ANALYST, AdminRole.REVIEWER);

    private final AdminAccessService access;
    private final CaseMembershipAccess memberships;
    private final FanReportRepository reports;
    private final EvidenceAssetRepository assets;
    private final ListingContextAnalysisRepository analyses;
    private final ListingContextObservationRepository observations;
    private final ListingContextAnalysisWriteService writer;
    private final EvidenceObjectStorage storage;
    private final ListingContextClient client;

    @Value("${ticket-defender.track2-listing-analysis.attempt-lease:PT5M}")
    private Duration attemptLease;

    public ListingContextAnalysisDto.View analyze(UUID fanReportId, UUID evidenceId, String key) {
        if (key == null || !KEY.matcher(key).matches())
            throw new CaseApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY");
        UUID membershipId = authorize(fanReportId, evidenceId);
        if (!client.configured()) throw new CaseApiException(HttpStatus.SERVICE_UNAVAILABLE, "ANALYZER_DISABLED");
        String keyDigest = CaseSnapshotManifest.sha256(key.toLowerCase());
        ListingContextAnalysisWriteService.Started started;
        try {
            started = writer.start(fanReportId, evidenceId, membershipId, keyDigest, leaseExpiry());
        } catch (DataIntegrityViolationException | OptimisticLockingFailureException collision) {
            started = new ListingContextAnalysisWriteService.Started(writer.replay(membershipId, evidenceId, keyDigest)
                    .orElseThrow(() -> new CaseApiException(HttpStatus.CONFLICT, "ANALYSIS_IN_PROGRESS")), false);
        }
        if (!started.created() || !started.analysis().inProgress()) return respond(started.analysis());
        return respond(run(started.analysis()));
    }

    public ListingContextAnalysisDto.Page history(UUID fanReportId, UUID evidenceId, int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE)
            throw new CaseApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        authorize(fanReportId, evidenceId);
        var found = analyses.findByFanReportIdAndEvidenceId(fanReportId, evidenceId, PageRequest.of(page, size,
                Sort.by("createdAt").descending().and(Sort.by("id").descending())));
        List<UUID> ids = found.stream().map(ListingContextAnalysis::getId).toList();
        List<ListingContextObservation> stored = ids.isEmpty() ? List.of() : observations.findByAnalysisIdIn(ids);
        return new ListingContextAnalysisDto.Page(found.stream().map(analysis -> ListingContextAnalysisDto.View.from(
                analysis, stored.stream().filter(item -> item.getAnalysisId().equals(analysis.getId())).toList()))
                .toList(), page, size, found.hasNext());
    }

    /** 저장된 제보 범위로 권한을 확인하고, 증거가 해당 제보 소속인지 확인합니다. 범위 밖 자원은 존재 여부를 드러내지 않습니다. */
    private UUID authorize(UUID fanReportId, UUID evidenceId) {
        FanReport report = reports.findById(fanReportId).orElseThrow(ListingContextAnalysisService::notFound);
        var actor = access.requireResourceAccess(
                new AdminResourceScope(report.getTenantId(), report.getEventId()), null, ROLES);
        EvidenceAsset asset = assets.findById(evidenceId).orElseThrow(ListingContextAnalysisService::notFound);
        if (!fanReportId.equals(asset.getFanReportId())) throw notFound();
        return memberships.actor(actor).getId();
    }

    private ListingContextAnalysis run(ListingContextAnalysis started) {
        EvidenceObjectStorage.VerifiedBytes original;
        try {
            original = storage.readVerifiedBytes(assetKey(started), started.getObjectMarker(),
                    ListingContextClient.MAX_IMAGE_BYTES);
        } catch (RuntimeException failure) {
            String code = storageFailure(failure);
            return fail(started, "STORAGE_UNAVAILABLE".equals(code) ? ListingContextAnalysis.Execution.STORAGE_FAILED
                    : ListingContextAnalysis.Execution.INPUT_REJECTED, code, null, null, null, null);
        }
        EvidenceObjectStorage.ByteVerification verified = original.verification();
        String rejected = null;
        if (verified.byteSize() != started.getExpectedByteSize() || original.bytes().length != verified.byteSize())
            rejected = "SIZE_MISMATCH";
        else if (!Objects.equals(verified.mediaType(), started.getMediaType())) rejected = "OBJECT_CHANGED";
        else if (!started.getMediaType().equals(sniff(original.bytes()))) rejected = "MEDIA_TYPE_MISMATCH";
        else if (!sameDigest(verified.sha256(), started.getExpectedSha256())
                || !sameDigest(sha256(original.bytes()), started.getExpectedSha256())) rejected = "HASH_MISMATCH";
        if (rejected != null)
            return fail(started, ListingContextAnalysis.Execution.INPUT_REJECTED, rejected, null, null,
                    verified.sha256(), verified.byteSize());
        ListingContextClient.Result result = client.analyze(started.getRequestId(), started.getEvidenceId(),
                started.getExpectedSha256(), started.getMediaType(), original.bytes());
        if (result instanceof ListingContextClient.Failure failure)
            return fail(started, ListingContextAnalysis.Execution.ANALYZER_FAILED, failure.errorCode(),
                    failure.httpStatus(), failure.analyzerErrorCode(), verified.sha256(), verified.byteSize());
        try {
            return writer.succeed(started.getId(), verified.sha256(), verified.byteSize(),
                    (ListingContextClient.Success) result);
        } catch (OptimisticLockingFailureException concurrent) {
            return writer.current(started.getId());
        }
    }

    private ListingContextAnalysis fail(ListingContextAnalysis started, ListingContextAnalysis.Execution stage,
            String code, Integer httpStatus, String analyzerCode, String sha256, Long byteSize) {
        try {
            return writer.fail(started.getId(), stage, code, httpStatus, analyzerCode, sha256, byteSize);
        } catch (OptimisticLockingFailureException concurrent) {
            return writer.current(started.getId());
        }
    }

    private ListingContextAnalysisDto.View respond(ListingContextAnalysis analysis) {
        log.info("Track 2 Listing Context 분석. 분석 ID: {}, 요청 ID: {}, 실행 상태: {}, 오류 코드: {}",
                analysis.getId(), analysis.getRequestId(), analysis.getExecutionStatus(), analysis.getErrorCode());
        return switch (analysis.getExecutionStatus()) {
            case SUCCEEDED -> ListingContextAnalysisDto.View.from(analysis,
                    observations.findByAnalysisIdIn(List.of(analysis.getId())));
            case IN_PROGRESS -> throw new CaseApiException(HttpStatus.CONFLICT, "ANALYSIS_IN_PROGRESS");
            case ABANDONED -> throw new CaseApiException(HttpStatus.CONFLICT, analysis.getErrorCode());
            case INPUT_REJECTED -> throw new CaseApiException(HttpStatus.UNPROCESSABLE_ENTITY, analysis.getErrorCode());
            case STORAGE_FAILED -> throw new CaseApiException(HttpStatus.SERVICE_UNAVAILABLE, analysis.getErrorCode());
            case ANALYZER_FAILED -> throw new CaseApiException(switch (analysis.getErrorCode()) {
                case "ANALYZER_UNAVAILABLE", "ANALYZER_CONNECTION_FAILED", "ANALYZER_DISABLED" ->
                        HttpStatus.SERVICE_UNAVAILABLE;
                case "ANALYZER_TIMEOUT", "ANALYZER_MODEL_TIMEOUT" -> HttpStatus.GATEWAY_TIMEOUT;
                case "ANALYSIS_INPUT_TOO_LARGE" -> HttpStatus.UNPROCESSABLE_ENTITY;
                default -> HttpStatus.BAD_GATEWAY;
            }, analysis.getErrorCode());
        };
    }

    private String assetKey(ListingContextAnalysis started) {
        EvidenceAsset asset = assets.findById(started.getEvidenceId())
                .filter(found -> found.getFanReportId().equals(started.getFanReportId()))
                .orElseThrow(ListingContextAnalysisService::notFound);
        if (!CaseSnapshotManifest.sha256(asset.getObjectKey()).equals(started.getObjectKeySha256())
                || !Objects.equals(asset.getObjectVersion(), started.getObjectMarker()))
            throw new S3EvidenceObjectStorage.ObjectMarkerChangedException();
        return asset.getObjectKey();
    }

    private String storageFailure(RuntimeException failure) {
        if (failure instanceof S3EvidenceObjectStorage.ObjectMarkerChangedException) return "OBJECT_CHANGED";
        if (failure instanceof S3EvidenceObjectStorage.ObjectSizeExceededException) return "ANALYSIS_INPUT_TOO_LARGE";
        if (failure instanceof S3Exception error) {
            if (error.statusCode() == 412) return "OBJECT_CHANGED";
            if (error.statusCode() == 404) {
                try {
                    storage.checkReady();
                    return "OBJECT_MISSING";
                } catch (RuntimeException unavailable) {
                    return "STORAGE_UNAVAILABLE";
                }
            }
        }
        return "STORAGE_UNAVAILABLE";
    }

    private LocalDateTime leaseExpiry() {
        Duration minimum = client.readTimeout().plusMinutes(3);
        Duration lease = attemptLease == null || attemptLease.compareTo(minimum) < 0 ? minimum : attemptLease;
        return LocalDateTime.now(ZoneOffset.UTC).plus(lease);
    }

    private static String sniff(byte[] bytes) {
        if (bytes.length >= 8 && (bytes[0] & 255) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
                && bytes[4] == 13 && bytes[5] == 10 && bytes[6] == 26 && bytes[7] == 10) return "image/png";
        if (bytes.length >= 3 && (bytes[0] & 255) == 0xff && (bytes[1] & 255) == 0xd8 && (bytes[2] & 255) == 0xff)
            return "image/jpeg";
        return null;
    }

    private static boolean sameDigest(String actual, String expected) {
        try {
            return MessageDigest.isEqual(HexFormat.of().parseHex(actual), HexFormat.of().parseHex(expected));
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static CaseApiException notFound() {
        return new CaseApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }
}
