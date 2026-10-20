package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.persistence.EvidenceAssetRepository;
import com.ticket.defender_core.adapter.out.persistence.FanReportRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantEventRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantRepository;
import com.ticket.defender_core.domain.AccessRecordStatus;
import com.ticket.defender_core.domain.EvidenceAsset;
import com.ticket.defender_core.domain.FanReport;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class FanReportPersistence {
    private final FanReportRepository reports;
    private final EvidenceAssetRepository assets;
    private final EvidenceObjectStorage storage;
    private final TenantEventRepository events;
    private final TenantRepository tenants;

    @Transactional
    public void reserve(FanReport report, List<EvidenceAsset> evidence) {
        var event = events.lockById(report.getEventId())
                .orElseThrow(FanReportPersistence::eventUnavailable);
        var tenant = tenants.lockById(report.getTenantId())
                .orElseThrow(FanReportPersistence::eventUnavailable);
        if (!event.getTenantId().equals(report.getTenantId()) || !event.isPublicIntakeEnabled()
                || !AccessRecordStatus.ACTIVE.name().equals(event.getStatus())
                || !AccessRecordStatus.ACTIVE.name().equals(tenant.getStatus()))
            throw eventUnavailable();
        reports.saveAndFlush(report);
        assets.saveAllAndFlush(evidence);
    }

    private static FanReportApiException eventUnavailable() {
        return new FanReportApiException(HttpStatus.NOT_FOUND, "EVENT_NOT_AVAILABLE");
    }

    @Transactional
    public void markReceived(UUID reportId, Map<UUID, String> versions) {
        FanReport report = reports.lockById(reportId).orElseThrow();
        List<EvidenceAsset> evidence = assets.findByFanReportIdOrderByCreatedAtAsc(reportId);
        if (evidence.size() != versions.size()) throw new IllegalStateException("Evidence count mismatch");
        for (EvidenceAsset asset : evidence) {
            if (!versions.containsKey(asset.getEvidenceId())) throw new IllegalStateException("Evidence ID mismatch");
            asset.markStored(versions.get(asset.getEvidenceId()));
        }
        report.markReceived();
        assets.flush();
        reports.flush();
    }

    @Transactional
    public void markStorageFailed(UUID reportId) {
        reports.lockById(reportId).orElseThrow().markStorageFailed();
    }

    @Transactional
    public void markCleaned(UUID reportId) {
        FanReport report = reports.lockById(reportId).orElseThrow();
        report.markStorageFailed();
        for (EvidenceAsset asset : assets.findByFanReportIdOrderByCreatedAtAsc(reportId)) asset.markFailed();
    }

    @Transactional
    public void cleanStale(UUID reportId, LocalDateTime before) {
        FanReport report = reports.lockById(reportId).orElseThrow();
        if (!report.getReceivedAt().isBefore(before)
                || (report.getIntakeStatus() != FanReport.IntakeStatus.PENDING_STORAGE
                && report.getIntakeStatus() != FanReport.IntakeStatus.STORAGE_FAILED)) return;
        for (EvidenceAsset asset : assets.findByFanReportIdOrderByCreatedAtAsc(reportId)) {
            storage.delete(asset.getObjectKey(), asset.getObjectVersion());
            asset.markFailed();
        }
        report.markStorageFailed();
    }
}
