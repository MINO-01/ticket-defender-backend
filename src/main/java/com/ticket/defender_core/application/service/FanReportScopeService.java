package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.persistence.FanReportRepository;
import com.ticket.defender_core.domain.FanReport;
import com.ticket.defender_core.security.EventScopeLookup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FanReportScopeService {
    private final FanReportRepository reports;
    private final EventScopeLookup events;

    /** Downstream analysis must use stored scope and a separately verified reservation performance reference. */
    @Transactional(readOnly = true)
    public Optional<VlmAnalysisService.ReportScope> verifiedReportScope(UUID fanReportId) {
        return reports.findById(fanReportId)
                .filter(report -> report.getIntakeStatus() == FanReport.IntakeStatus.RECEIVED
                        || report.getIntakeStatus() == FanReport.IntakeStatus.ANALYSIS_PENDING)
                .filter(report -> report.getVerifiedPerformanceRef() != null
                        && !report.getVerifiedPerformanceRef().isBlank())
                .filter(report -> events.findTenantId(report.getEventId())
                        .filter(report.getTenantId()::equals).isPresent())
                .map(report -> new VlmAnalysisService.ReportScope(
                        report.getTenantId(), report.getEventId(), report.getVerifiedPerformanceRef()));
    }
}
