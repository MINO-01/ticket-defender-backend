package com.ticket.defender_core.application.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicRequestLimiterTest {
    private PublicRequestLimiter limiter;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource datasource = new DriverManagerDataSource(
                "jdbc:h2:mem:public-quota;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(datasource);
        jdbc.execute("DROP TABLE IF EXISTS public_request_quota");
        jdbc.execute("CREATE TABLE public_request_quota (scope VARCHAR(16) NOT NULL, "
                + "actor_digest CHAR(64) NOT NULL, window_started_at TIMESTAMP NOT NULL, "
                + "attempts INT NOT NULL, PRIMARY KEY (scope, actor_digest))");
        FanReportCrypto crypto = new FanReportCrypto(false, "");
        crypto.initialize();
        limiter = new PublicRequestLimiter(jdbc, crypto);
    }

    @Test
    void limitsIntakePerSourceAndLookupPerReceipt() {
        limiter.checkIntake("192.0.2.1");
        limiter.checkIntake("192.0.2.1");
        assertThatThrownBy(() -> limiter.checkIntake("192.0.2.1"))
                .isInstanceOf(FanReportApiException.class).hasMessage("RATE_LIMITED");
        for (int i = 0; i < 5; i++) limiter.checkLookup("192.0.2." + (i + 2), "FR-synthetic");
        assertThatThrownBy(() -> limiter.checkLookup("192.0.2.8", "FR-synthetic"))
                .isInstanceOf(FanReportApiException.class).hasMessage("RATE_LIMITED");
    }

    @Test
    void concurrentRequestsCannotExceedDbQuota() throws Exception {
        int clients = 8;
        CountDownLatch ready = new CountDownLatch(clients);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger permitted = new AtomicInteger();
        try (var workers = Executors.newFixedThreadPool(clients)) {
            for (int i = 0; i < clients; i++) workers.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    limiter.checkIntake("198.51.100.9");
                    permitted.incrementAndGet();
                } catch (FanReportApiException limited) {
                    assertThat(limited.code()).isEqualTo("RATE_LIMITED");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            workers.shutdown();
            assertThat(workers.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(permitted.get()).isBetween(1, 2);
    }
}
