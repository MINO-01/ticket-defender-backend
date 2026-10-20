package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.persistence.FanReportRepository;
import com.ticket.defender_core.domain.FanReport;
import com.ticket.defender_core.security.EventScopeLookup;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class FanReportScopeServiceTest {
    private final FanReportRepository reports = mock(FanReportRepository.class);
    private final EventScopeLookup events = mock(EventScopeLookup.class);
    private final FanReportScopeService service = new FanReportScopeService(reports, events);
    private final UUID tenantId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();
    private final UUID reportId = UUID.randomUUID();

    @Test
    void submittedShowtimeNeverBecomesReservationPerformanceRef() {
        FanReport report = report();
        report.markReceived();
        when(reports.findById(reportId)).thenReturn(Optional.of(report));
        assertThat(service.verifiedReportScope(reportId)).isEmpty();
        verifyNoInteractions(events);
    }

    @Test
    void verifiedReferenceStillRequiresStoredTenantEventScope() {
        FanReport report = report();
        report.markReceived();
        ReflectionTestUtils.setField(report, "verifiedPerformanceRef", "round-1");
        when(reports.findById(reportId)).thenReturn(Optional.of(report));
        when(events.findTenantId(eventId)).thenReturn(Optional.of(UUID.randomUUID()), Optional.of(tenantId));
        assertThat(service.verifiedReportScope(reportId)).isEmpty();
        assertThat(service.verifiedReportScope(reportId)).contains(
                new VlmAnalysisService.ReportScope(tenantId, eventId, "round-1"));
    }

    private FanReport report() {
        return new FanReport(reportId, "FR-synthetic", tenantId, eventId,
                LocalDateTime.parse("2026-11-01T19:30:00"), null, FanReport.ReportType.OTHER,
                "A synthetic suspected listing", null, null, null, null,
                LocalDateTime.parse("2026-10-20T12:00:00"), "a".repeat(64), "b".repeat(64), "c".repeat(64));
    }
}
