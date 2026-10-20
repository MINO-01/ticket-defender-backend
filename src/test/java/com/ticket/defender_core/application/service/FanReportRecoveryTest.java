package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.persistence.EvidenceAssetRepository;
import com.ticket.defender_core.adapter.out.persistence.FanReportRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantEventRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantRepository;
import com.ticket.defender_core.domain.EvidenceAsset;
import com.ticket.defender_core.domain.FanReport;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class FanReportRecoveryTest {
    private final FanReportRepository reports = mock(FanReportRepository.class);
    private final EvidenceAssetRepository assets = mock(EvidenceAssetRepository.class);
    private final EvidenceObjectStorage storage = mock(EvidenceObjectStorage.class);
    private final FanReportPersistence persistence = new FanReportPersistence(reports, assets, storage,
            mock(TenantEventRepository.class), mock(TenantRepository.class));

    @Test
    void stalePartialUploadIsDeletedAndMarkedFailed() {
        UUID reportId = UUID.randomUUID();
        FanReport report = report(reportId);
        EvidenceAsset first = asset(reportId, "fan-reports/first");
        EvidenceAsset second = asset(reportId, "fan-reports/second");
        when(reports.lockById(reportId)).thenReturn(Optional.of(report));
        when(assets.findByFanReportIdOrderByCreatedAtAsc(reportId)).thenReturn(List.of(first, second));

        persistence.cleanStale(reportId, LocalDateTime.parse("2026-10-20T13:00:00"));

        verify(storage).delete("fan-reports/first", null);
        verify(storage).delete("fan-reports/second", null);
        assertThat(report.getIntakeStatus()).isEqualTo(FanReport.IntakeStatus.STORAGE_FAILED);
        assertThat(first.getStorageStatus()).isEqualTo(EvidenceAsset.StorageStatus.FAILED);
        assertThat(second.getStorageStatus()).isEqualTo(EvidenceAsset.StorageStatus.FAILED);
    }

    @Test
    void deleteFailureLeavesPendingRowForNextSweep() {
        UUID reportId = UUID.randomUUID();
        FanReport report = report(reportId);
        EvidenceAsset asset = asset(reportId, "fan-reports/first");
        when(reports.lockById(reportId)).thenReturn(Optional.of(report));
        when(assets.findByFanReportIdOrderByCreatedAtAsc(reportId)).thenReturn(List.of(asset));
        doThrow(new IllegalStateException("synthetic object store outage"))
                .when(storage).delete("fan-reports/first", null);

        assertThatThrownBy(() -> persistence.cleanStale(reportId,
                LocalDateTime.parse("2026-10-20T13:00:00"))).isInstanceOf(IllegalStateException.class);
        assertThat(report.getIntakeStatus()).isEqualTo(FanReport.IntakeStatus.PENDING_STORAGE);
        assertThat(asset.getStorageStatus()).isEqualTo(EvidenceAsset.StorageStatus.PENDING);
    }

    @Test
    void completedOrRecentReportIsNeverDeleted() {
        UUID reportId = UUID.randomUUID();
        FanReport report = report(reportId);
        when(reports.lockById(reportId)).thenReturn(Optional.of(report));
        persistence.cleanStale(reportId, LocalDateTime.parse("2026-10-20T11:00:00"));
        report.markReceived();
        persistence.cleanStale(reportId, LocalDateTime.parse("2026-10-20T13:00:00"));
        verifyNoInteractions(storage, assets);
    }

    private FanReport report(UUID id) {
        return new FanReport(id, "FR-synthetic", UUID.randomUUID(), UUID.randomUUID(),
                LocalDateTime.parse("2026-11-01T19:30:00"), null, FanReport.ReportType.OTHER,
                "A synthetic suspected listing description", null, null, null, null,
                LocalDateTime.parse("2026-10-20T12:00:00"), "a".repeat(64), "b".repeat(64), "c".repeat(64));
    }

    private EvidenceAsset asset(UUID reportId, String key) {
        return new EvidenceAsset(UUID.randomUUID(), reportId, key, "image/png", 4, "a".repeat(64),
                LocalDateTime.parse("2026-10-20T12:00:00"), LocalDateTime.parse("2027-01-18T12:00:00"));
    }
}
