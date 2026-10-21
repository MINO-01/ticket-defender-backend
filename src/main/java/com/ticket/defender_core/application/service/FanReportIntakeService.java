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
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
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
            throw new FanReportApiException(HttpStatus.SERVICE_UNAVAILABLE, "INTAKE_STORAGE_UNAVAILABLE");
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
            try { persistence.markStorageFailed(id); } catch (Exception ignored) { /* stale PENDING rows are also swept */ }
            boolean cleaned = true;
            for (EvidenceAsset asset : evidence) {
                try { storage.delete(asset.getObjectKey(), uploaded.get(asset.getEvidenceId())); }
                catch (Exception deleteFailure) { cleaned = false; }
            }
            if (cleaned) {
                try { persistence.markCleaned(id); } catch (Exception ignored) { /* sweeper will retry */ }
            }
            throw new FanReportApiException(HttpStatus.SERVICE_UNAVAILABLE, "INTAKE_STORAGE_UNAVAILABLE");
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
            throw new FanReportApiException(HttpStatus.SERVICE_UNAVAILABLE, "INTAKE_STORAGE_UNAVAILABLE");
        }
    }

    private FanReportReceipt replay(FanReport report, String requestDigest, String stableKey, byte[] recoveryKey) {
        String code = crypto.lookupCode(recoveryKey, stableKey, report.getReceiptNumber());
        if (!crypto.verifyLookup(report.getReceiptNumber(), code, report.getLookupSecretDigest()))
            throw new FanReportApiException(HttpStatus.NOT_FOUND, "REPORT_NOT_FOUND");
        if (!report.getRequestDigest().equals(requestDigest))
            throw new FanReportApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT");
        if (report.getIntakeStatus() == FanReport.IntakeStatus.PENDING_STORAGE)
            throw new FanReportApiException(HttpStatus.CONFLICT, "INTAKE_IN_PROGRESS");
        if (report.getIntakeStatus() == FanReport.IntakeStatus.STORAGE_FAILED)
            throw new FanReportApiException(HttpStatus.SERVICE_UNAVAILABLE, "INTAKE_STORAGE_UNAVAILABLE");
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
