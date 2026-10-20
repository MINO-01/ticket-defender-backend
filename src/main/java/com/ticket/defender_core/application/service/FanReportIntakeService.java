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
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FanReportIntakeService {
    private final PublicEventLookup eventLookup;
    private final EvidenceImageValidator imageValidator;
    private final FanReportCrypto crypto;
    private final FanReportRepository reports;
    private final FanReportPersistence persistence;
    private final EvidenceObjectStorage storage;

    @Value("${ticket-defender.fan-report.retention-days:90}")
    private int retentionDays;

    public FanReportReceipt submit(FanReportSubmission submission, List<MultipartFile> files, String suppliedKey) {
        if (suppliedKey == null || !suppliedKey.matches("[A-Za-z0-9._-]{16,128}"))
            throw new FanReportApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY");
        TenantEvent event = eventLookup.requireOpenEvent(submission.eventId());
        List<ValidatedImage> images = imageValidator.validate(files);
        String keyDigest = crypto.idempotencyDigest(suppliedKey);
        String requestDigest = crypto.requestDigest(submission, images);
        storage.checkReady();

        FanReport existing = reports.findByIdempotencyKeyDigest(keyDigest).orElse(null);
        if (existing != null) return replay(existing, requestDigest);

        UUID id = UUID.randomUUID();
        String receipt = crypto.newReceiptNumber();
        String code = crypto.newLookupCode();
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
            if (raced != null) return replay(raced, requestDigest);
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

    private FanReportReceipt replay(FanReport report, String requestDigest) {
        if (!report.getRequestDigest().equals(requestDigest))
            throw new FanReportApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT");
        if (report.getIntakeStatus() == FanReport.IntakeStatus.PENDING_STORAGE)
            throw new FanReportApiException(HttpStatus.CONFLICT, "INTAKE_IN_PROGRESS");
        if (report.getIntakeStatus() == FanReport.IntakeStatus.STORAGE_FAILED)
            throw new FanReportApiException(HttpStatus.SERVICE_UNAVAILABLE, "INTAKE_STORAGE_UNAVAILABLE");
        // The lookup code is deliberately not persisted or reconstructed on replay.
        return new FanReportReceipt(report.getFanReportId(), report.getReceiptNumber(), null,
                report.getIntakeStatus(), report.getReceivedAt().atOffset(ZoneOffset.UTC));
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
