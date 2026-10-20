package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.in.web.dto.FanReportReceipt;
import com.ticket.defender_core.adapter.in.web.dto.FanReportSubmission;
import com.ticket.defender_core.adapter.out.persistence.FanReportRepository;
import com.ticket.defender_core.adapter.out.persistence.EvidenceAssetRepository;
import com.ticket.defender_core.application.service.EvidenceObjectStorage;
import com.ticket.defender_core.application.service.FanReportApiException;
import com.ticket.defender_core.application.service.FanReportIntakeService;
import com.ticket.defender_core.application.service.FanReportPersistence;
import com.ticket.defender_core.application.service.PublicRequestLimiter;
import com.ticket.defender_core.domain.FanReport;
import com.ticket.defender_core.domain.EvidenceAsset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@EnabledIfEnvironmentVariable(named = "TD_ISSUE32_MYSQL_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:13307/td_issue32.*")
class FanReportSchemaValidateMySqlTest {
    @Autowired private FanReportRepository reports;
    @Autowired private EvidenceAssetRepository assets;
    @Autowired private FanReportIntakeService intake;
    @Autowired private FanReportPersistence persistence;
    @Autowired private PublicRequestLimiter limiter;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private EvidenceObjectStorage storage;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TD_ISSUE32_MYSQL_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("TD_ISSUE32_MYSQL_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("TD_ISSUE32_MYSQL_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Test
    void validatesNewSchemaAndReadsMigratedReport() {
        assertThat(reports.findByReceiptNumber("FR-synthetic-1")).isPresent();
    }

    @Test
    void mysqlQuotaIsSharedAndLimitsAttempts() {
        String remote = "synthetic-source-" + UUID.randomUUID();
        limiter.checkIntake(remote);
        limiter.checkIntake(remote);
        assertThatThrownBy(() -> limiter.checkIntake(remote))
                .isInstanceOf(FanReportApiException.class).hasMessage("RATE_LIMITED");
    }

    @Test
    void simultaneousIdenticalKeysCreateOneReportAndOneLookupCode() throws Exception {
        UUID event = UUID.fromString("33333333-3333-4333-8333-333333333333");
        jdbc.update("UPDATE events SET public_intake_enabled=b'1' WHERE id=?", event.toString());
        String unique = UUID.randomUUID().toString();
        String description = "Synthetic concurrent report " + unique;
        FanReportSubmission request = new FanReportSubmission(event,
                LocalDateTime.parse("2026-11-01T19:30:00"), null, FanReport.ReportType.OTHER,
                description, null, null, null, null);
        int clients = 8;
        CountDownLatch ready = new CountDownLatch(clients);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<FanReportReceipt>> calls = new ArrayList<>();
        try (var workers = Executors.newFixedThreadPool(clients)) {
            for (int i = 0; i < clients; i++) {
                calls.add(workers.submit(() -> {
                    ready.countDown();
                    start.await();
                    try { return intake.submit(request, List.of(), "key-" + unique); }
                    catch (FanReportApiException inProgress) {
                        assertThat(inProgress.code()).isEqualTo("INTAKE_IN_PROGRESS");
                        return null;
                    }
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<FanReportReceipt> call : calls) call.get(30, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fan_reports WHERE description=?", Integer.class,
                description)).isEqualTo(1);
        assertThat(calls.stream().map(call -> {
            try { return call.get(); } catch (Exception error) { throw new RuntimeException(error); }
        }).filter(receipt -> receipt != null && receipt.lookupCode() != null).count()).isEqualTo(1);
    }

    @Test
    void stalePartialStorageIsReconciledInMySql() {
        UUID reportId = UUID.randomUUID();
        UUID tenant = UUID.fromString("11111111-1111-4111-8111-111111111111");
        UUID event = UUID.fromString("33333333-3333-4333-8333-333333333333");
        LocalDateTime received = LocalDateTime.parse("2026-10-20T10:00:00");
        String uniqueDigest = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        reports.saveAndFlush(new FanReport(reportId, "FR-" + UUID.randomUUID(), tenant, event,
                LocalDateTime.parse("2026-11-01T19:30:00"), null, FanReport.ReportType.OTHER,
                "A synthetic interrupted storage report", null, null, null, null, received,
                uniqueDigest, "d".repeat(64), "e".repeat(64)));
        UUID evidenceId = UUID.randomUUID();
        String objectKey = "fan-reports/" + reportId + "/" + evidenceId;
        assets.saveAndFlush(new EvidenceAsset(evidenceId, reportId, objectKey, "image/png", 4,
                "a".repeat(64), received, received.plusDays(90)));

        persistence.cleanStale(reportId, LocalDateTime.parse("2026-10-20T11:00:00"));

        verify(storage).delete(objectKey, null);
        assertThat(reports.findById(reportId).orElseThrow().getIntakeStatus())
                .isEqualTo(FanReport.IntakeStatus.STORAGE_FAILED);
        assertThat(assets.findById(evidenceId).orElseThrow().getStorageStatus())
                .isEqualTo(EvidenceAsset.StorageStatus.FAILED);
    }
}
