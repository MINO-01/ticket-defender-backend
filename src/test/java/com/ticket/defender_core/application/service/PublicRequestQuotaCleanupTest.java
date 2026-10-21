package com.ticket.defender_core.application.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class PublicRequestQuotaCleanupTest {
    @Test
    void removesOnlyExpiredQuotaIdentifiers() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:quota-cleanup;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE public_request_quota (scope VARCHAR(16), actor_digest CHAR(64), "
                + "window_started_at TIMESTAMP NOT NULL, attempts INT, PRIMARY KEY (scope, actor_digest))");
        jdbc.update("INSERT INTO public_request_quota VALUES (?,?,?,?)", "LOOKUP_IP", "a".repeat(64),
                Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC).minusHours(49)), 1);
        jdbc.update("INSERT INTO public_request_quota VALUES (?,?,?,?)", "LOOKUP_IP", "b".repeat(64),
                Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC).minusHours(1)), 1);

        new PublicRequestQuotaCleanup(jdbc).removeExpiredWindows();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public_request_quota", Integer.class)).isEqualTo(1);
    }
}
