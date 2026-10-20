package com.ticket.defender_core.application.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
public class PublicRequestLimiter {
    private final JdbcTemplate jdbc;
    private final FanReportCrypto crypto;

    public void checkIntake(String remoteAddress) {
        count("INTAKE_IP", remoteAddress, 2, 60);
        count("INTAKE_HOUR", remoteAddress, 20, 3600);
    }

    public void checkLookup(String remoteAddress, String receiptNumber) {
        count("LOOKUP_IP", remoteAddress, 10, 60);
        count("LOOKUP_HOUR", remoteAddress, 100, 3600);
        count("LOOKUP_RECEIPT", receiptNumber, 5, 60);
    }

    private void count(String scope, String actor, int limit, int windowSeconds) {
        String digest = crypto.actorDigest(actor);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime expiredBefore = now.minusSeconds(windowSeconds);
        jdbc.update("""
                INSERT INTO public_request_quota (scope, actor_digest, window_started_at, attempts)
                VALUES (?, ?, ?, 1)
                ON DUPLICATE KEY UPDATE
                  attempts = CASE WHEN window_started_at < ? THEN 1 ELSE attempts + 1 END,
                  window_started_at = CASE WHEN window_started_at < ? THEN ? ELSE window_started_at END
                """, scope, digest, Timestamp.valueOf(now), Timestamp.valueOf(expiredBefore),
                Timestamp.valueOf(expiredBefore), Timestamp.valueOf(now));
        Integer attempts = jdbc.queryForObject(
                "SELECT attempts FROM public_request_quota WHERE scope = ? AND actor_digest = ?",
                Integer.class, scope, digest);
        if (attempts == null || attempts > limit) {
            throw new FanReportApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED");
        }
    }
}
