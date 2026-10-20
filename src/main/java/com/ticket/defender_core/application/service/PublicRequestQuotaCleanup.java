package com.ticket.defender_core.application.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ticket-defender.fan-report.enabled", havingValue = "true")
public class PublicRequestQuotaCleanup {
    private final JdbcTemplate jdbc;

    @Scheduled(initialDelayString = "21600000", fixedDelayString = "21600000")
    public void removeExpiredWindows() {
        jdbc.update("DELETE FROM public_request_quota WHERE window_started_at < ?",
                Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC).minusHours(48)));
    }
}
