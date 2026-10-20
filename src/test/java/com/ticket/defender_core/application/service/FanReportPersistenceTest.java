package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.persistence.EvidenceAssetRepository;
import com.ticket.defender_core.adapter.out.persistence.FanReportRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantEventRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantRepository;
import com.ticket.defender_core.domain.AccessRecordStatus;
import com.ticket.defender_core.domain.FanReport;
import com.ticket.defender_core.domain.Tenant;
import com.ticket.defender_core.domain.TenantEvent;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class FanReportPersistenceTest {
    private final FanReportRepository reports = mock(FanReportRepository.class);
    private final EvidenceAssetRepository assets = mock(EvidenceAssetRepository.class);
    private final TenantEventRepository events = mock(TenantEventRepository.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final FanReportPersistence persistence = new FanReportPersistence(reports, assets,
            mock(EvidenceObjectStorage.class), events, tenants);
    private final UUID tenantId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();

    @Test
    void eventClosedAfterInitialLookupCannotBeReserved() {
        TenantEvent event = new TenantEvent(eventId, tenantId, "ref", "Synthetic event", AccessRecordStatus.ACTIVE);
        when(events.lockById(eventId)).thenReturn(Optional.of(event));
        when(tenants.lockById(tenantId)).thenReturn(Optional.of(new Tenant(tenantId, "Synthetic tenant",
                AccessRecordStatus.ACTIVE)));

        assertThatThrownBy(() -> persistence.reserve(report(), List.of()))
                .isInstanceOf(FanReportApiException.class).hasMessage("EVENT_NOT_AVAILABLE");
        verify(reports, never()).saveAndFlush(any());
    }

    @Test
    void mismatchedTenantCannotBePersistedEvenWithValidEventId() {
        TenantEvent event = new TenantEvent(eventId, UUID.randomUUID(), "ref", "Synthetic event",
                AccessRecordStatus.ACTIVE);
        event.setPublicIntakeEnabled(true);
        when(events.lockById(eventId)).thenReturn(Optional.of(event));
        when(tenants.lockById(tenantId)).thenReturn(Optional.of(new Tenant(tenantId, "Synthetic tenant",
                AccessRecordStatus.ACTIVE)));

        assertThatThrownBy(() -> persistence.reserve(report(), List.of()))
                .isInstanceOf(FanReportApiException.class).hasMessage("EVENT_NOT_AVAILABLE");
        verify(reports, never()).saveAndFlush(any());
    }

    private FanReport report() {
        return new FanReport(UUID.randomUUID(), "FR-synthetic", tenantId, eventId,
                LocalDateTime.parse("2026-11-01T19:30:00"), null, FanReport.ReportType.OTHER,
                "A synthetic suspected listing description", null, null, null, null,
                LocalDateTime.parse("2026-10-20T12:00:00"), "a".repeat(64), "b".repeat(64), "c".repeat(64));
    }
}
