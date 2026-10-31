package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.FanReportLookupRequest;
import com.ticket.defender_core.adapter.in.web.dto.FanReportLookupResponse;
import com.ticket.defender_core.adapter.in.web.dto.FanReportReceipt;
import com.ticket.defender_core.adapter.in.web.dto.FanReportSubmission;
import com.ticket.defender_core.adapter.out.persistence.FanReportRepository;
import com.ticket.defender_core.domain.EvidenceAsset;
import com.ticket.defender_core.domain.FanReport;
import com.ticket.defender_core.domain.TenantEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class FanReportIntakeService {
    /** 같은 키의 STORAGE_FAILED 행이 DB에 기록된 것을 확인한 확정 실패이며, 새 키로만 다시 접수할 수 있다. */
    public static final String STORAGE_FAILED_CODE = "INTAKE_STORAGE_UNAVAILABLE";
    /** 접수 결과를 확정할 수 없으므로 같은 키와 복구 비밀로 재시도해야 한다. */
    public static final String OUTCOME_UNKNOWN_CODE = "INTAKE_OUTCOME_UNKNOWN";
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile(
            "(?i)[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    private final PublicEventLookup eventLookup;
    private final EvidenceImageValidator imageValidator;
    private final FanReportCrypto crypto;
    private final FanReportRepository reports;
    private final FanReportPersistence persistence;
    private final EvidenceObjectStorage storage;

    @Value("${ticket-defender.fan-report.retention-days:90}")
    private int retentionDays;

    public FanReportReceipt submit(FanReportSubmission submission, List<MultipartFile> files,
                                   String suppliedKey, String suppliedRecoverySecret) {
        if (suppliedKey == null || !IDEMPOTENCY_KEY.matcher(suppliedKey).matches())
            throw new FanReportApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY");
        byte[] recoveryKey;
        try {
            recoveryKey = crypto.recoveryKey(suppliedRecoverySecret);
        } catch (IllegalArgumentException invalid) {
            throw new FanReportApiException(HttpStatus.BAD_REQUEST, "INVALID_RECOVERY_SECRET");
        }
        String stableKey = suppliedKey.toLowerCase(Locale.ROOT);
        List<ValidatedImage> images = imageValidator.validate(files);
        String keyDigest = crypto.idempotencyDigest(stableKey);
        String requestDigest = crypto.requestDigest(submission, images, recoveryKey);

        FanReport existing = reports.findByIdempotencyKeyDigest(keyDigest).orElse(null);
        if (existing != null) return replay(existing, requestDigest, stableKey, recoveryKey);
        // Older reports used the server HMAC for this column and issued a non-recoverable random code.
        // Reject the original key instead of creating a second report under the new digest format.
        if (reports.findByIdempotencyKeyDigest(crypto.legacyIdempotencyDigest(suppliedKey)).isPresent())
            throw new FanReportApiException(HttpStatus.CONFLICT, "LEGACY_RECOVERY_UNAVAILABLE");
        TenantEvent event = eventLookup.requireOpenEvent(submission.eventId());
        storage.checkReady();

        UUID id = UUID.randomUUID();
        String receipt = crypto.newReceiptNumber();
        String code = crypto.lookupCode(recoveryKey, stableKey, receipt);
        // DATETIME(6) 저장값과 첫 응답이 같도록 마이크로초로 맞춰 재요청 200 응답의 receivedAt을 일치시킨다.
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        if (retentionDays < 1 || retentionDays > 3650)
            throw new IllegalStateException("Fan report retention days must be between 1 and 3650");
        FanReport report = new FanReport(id, receipt, event.getTenantId(), event.getId(),
                submission.performanceAt(), submission.seatArea(), submission.reportType(),
                submission.description(), submission.listingUrl(), submission.platform(), submission.seller(),
                submission.parsedAskingPrice(), now, keyDigest, requestDigest, crypto.lookupDigest(receipt, code));
        report.setRetentionUntil(now.plusDays(retentionDays));
        List<EvidenceAsset> evidence = new ArrayList<>();
        for (ValidatedImage image : images) {
            UUID assetId = UUID.randomUUID();
            // Neither supplied file name nor user-controlled path contributes to this key.
            String objectKey = "fan-reports/" + id + "/" + assetId;
            evidence.add(new EvidenceAsset(assetId, id, objectKey, image.mediaType(),
                    image.byteSize(), image.sha256(), now, now.plusDays(retentionDays)));
        }
        try {
            persistence.reserve(report, evidence);
        } catch (DataIntegrityViolationException conflict) {
            FanReport raced = reports.findByIdempotencyKeyDigest(keyDigest).orElse(null);
            if (raced != null) return replay(raced, requestDigest, stableKey, recoveryKey);
            // 예약이 롤백되어 같은 키의 실패 행이 없으므로 확정 실패가 아니다.
            throw outcomeUnknown();
        }

        Map<UUID, String> uploaded = new HashMap<>();
        try {
            for (int i = 0; i < images.size(); i++) {
                EvidenceAsset asset = evidence.get(i);
                String version = storage.put(asset.getObjectKey(), images.get(i));
                uploaded.put(asset.getEvidenceId(), version);
            }
        } catch (Exception uploadFailure) {
            // Keys were committed before upload. A crash or failed delete is recoverable from the rows.
            boolean failurePersisted = false;
            try {
                persistence.markStorageFailed(id);
                failurePersisted = true;
            } catch (Exception ignored) { /* stale PENDING rows are also swept */ }
            boolean cleaned = true;
            for (EvidenceAsset asset : evidence) {
                try { storage.delete(asset.getObjectKey(), uploaded.get(asset.getEvidenceId())); }
                catch (Exception deleteFailure) { cleaned = false; }
            }
            if (cleaned) {
                try {
                    persistence.markCleaned(id);
                    failurePersisted = true;
                } catch (Exception ignored) { /* sweeper will retry */ }
            }
            // 실패 상태 기록을 확인하지 못하면 같은 키 재시도로 처리 중 또는 확정 실패를 다시 확인하게 한다.
            throw failurePersisted ? storageFailed() : outcomeUnknown();
        }
        try {
            persistence.markReceived(id, uploaded);
            return new FanReportReceipt(id, receipt, code, FanReport.IntakeStatus.RECEIVED,
                    now.atOffset(ZoneOffset.UTC));
        } catch (Exception finalizationFailure) {
            // A lost DB acknowledgement might follow a committed RECEIVED row. Re-read under a DB
            // lock before deleting anything; cleanStale skips rows already committed as RECEIVED.
            try { persistence.cleanStale(id, LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1)); }
            catch (Exception ignored) { /* persisted PENDING rows are swept after recovery */ }
            // 커밋 결과는 다시 읽은 영속 상태로만 판정하고, 확인하지 못하면 성공이나 실패로 단정하지 않는다.
            FanReport.IntakeStatus persisted;
            try {
                persisted = reports.findById(id).map(FanReport::getIntakeStatus).orElse(null);
            } catch (Exception unreadable) {
                throw outcomeUnknown();
            }
            if (persisted == FanReport.IntakeStatus.RECEIVED)
                return new FanReportReceipt(id, receipt, code, FanReport.IntakeStatus.RECEIVED,
                        now.atOffset(ZoneOffset.UTC));
            if (persisted == FanReport.IntakeStatus.STORAGE_FAILED) throw storageFailed();
            throw outcomeUnknown();
        }
    }

    private static FanReportApiException storageFailed() {
        return new FanReportApiException(HttpStatus.SERVICE_UNAVAILABLE, STORAGE_FAILED_CODE);
    }

    private static FanReportApiException outcomeUnknown() {
        return new FanReportApiException(HttpStatus.SERVICE_UNAVAILABLE, OUTCOME_UNKNOWN_CODE);
    }

    private FanReportReceipt replay(FanReport report, String requestDigest, String stableKey, byte[] recoveryKey) {
        String code = crypto.lookupCode(recoveryKey, stableKey, report.getReceiptNumber());
        if (!crypto.verifyLookup(report.getReceiptNumber(), code, report.getLookupSecretDigest()))
            throw new FanReportApiException(HttpStatus.NOT_FOUND, "REPORT_NOT_FOUND");
        if (!report.getRequestDigest().equals(requestDigest))
            throw new FanReportApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT");
        if (report.getIntakeStatus() == FanReport.IntakeStatus.PENDING_STORAGE)
            throw new FanReportApiException(HttpStatus.CONFLICT, "INTAKE_IN_PROGRESS");
        if (report.getIntakeStatus() == FanReport.IntakeStatus.STORAGE_FAILED) throw storageFailed();
        return new FanReportReceipt(report.getFanReportId(), report.getReceiptNumber(), code,
                report.getIntakeStatus(), report.getReceivedAt().atOffset(ZoneOffset.UTC), true);
    }

    public FanReportLookupResponse lookup(FanReportLookupRequest request) {
        FanReport report = reports.findByReceiptNumber(request.receiptNumber())
                .orElseThrow(() -> new FanReportApiException(HttpStatus.NOT_FOUND, "REPORT_NOT_FOUND"));
        if (!crypto.verifyLookup(report.getReceiptNumber(), request.lookupCode(), report.getLookupSecretDigest())
                || report.getIntakeStatus() == FanReport.IntakeStatus.PENDING_STORAGE
                || report.getIntakeStatus() == FanReport.IntakeStatus.STORAGE_FAILED)
            throw new FanReportApiException(HttpStatus.NOT_FOUND, "REPORT_NOT_FOUND");
        return new FanReportLookupResponse(report.getIntakeStatus(), report.getReceivedAt().atOffset(ZoneOffset.UTC));
    }
}
