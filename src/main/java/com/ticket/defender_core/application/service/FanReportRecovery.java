package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.persistence.FanReportRepository;
import com.ticket.defender_core.domain.FanReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ticket-defender.fan-report.enabled", havingValue = "true")
public class FanReportRecovery {
    private final FanReportRepository reports;
    private final FanReportPersistence persistence;

    @Scheduled(initialDelayString = "${ticket-defender.fan-report.recovery-delay-ms:600000}",
            fixedDelayString = "${ticket-defender.fan-report.recovery-delay-ms:600000}")
    public void recoverStale() {
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(30);
        for (FanReport report : reports.findTop50ByIntakeStatusInAndReceivedAtBeforeOrderByReceivedAtAsc(
                List.of(FanReport.IntakeStatus.PENDING_STORAGE, FanReport.IntakeStatus.STORAGE_FAILED), before)) {
            try {
                persistence.cleanStale(report.getFanReportId(), before);
            } catch (Exception ignored) {
                // A later sweep retries; never log the object key or fan report identifier.
                log.warn("Fan report evidence cleanup retry failed");
            }
        }
    }
}
