package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.out.persistence.AdminMembershipRepository;
import com.ticket.defender_core.adapter.out.persistence.BulkLedgerImportJobRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantEventRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantRepository;
import com.ticket.defender_core.adapter.out.persistence.VerifiedPerformanceRepository;
import com.ticket.defender_core.application.service.BulkLedgerCsvStagingParser;
import com.ticket.defender_core.application.service.BulkLedgerStagingWriter;
import com.ticket.defender_core.domain.AccessRecordStatus;
import com.ticket.defender_core.domain.AdminMembershipEntity;
import com.ticket.defender_core.domain.BulkLedgerImportJob;
import com.ticket.defender_core.domain.Tenant;
import com.ticket.defender_core.domain.TenantEvent;
import com.ticket.defender_core.domain.VerifiedPerformance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** BL-02 duplicate and fence behavior against the disposable MySQL 8 CI schema. */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@EnabledIfEnvironmentVariable(named = "TD_ISSUE32_MYSQL_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/td_issue32_ci.*")
class BulkLedgerStagingMySqlTest {
    @Autowired private BulkLedgerStagingWriter staging;
    @Autowired private BulkLedgerImportJobRepository jobs;
    @Autowired private TenantRepository tenants;
    @Autowired private TenantEventRepository events;
    @Autowired private AdminMembershipRepository memberships;
    @Autowired private VerifiedPerformanceRepository performances;
    @Autowired private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TD_ISSUE32_MYSQL_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("TD_ISSUE32_MYSQL_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("TD_ISSUE32_MYSQL_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Test
    void retriesDoNotDuplicateRowsAndStaleFencesCannotWriteOrReplaceNewState() {
        Stage stage = stagingJob();
        BulkLedgerCsvStagingParser.ParsedRow first = row(stage, 2, "c");
        BulkLedgerStagingWriter.Session initial = staging.open(stage.jobId(), stage.tenantId(), stage.fence());
        initial.accept(first); initial.finish();
        BulkLedgerStagingWriter.Session retry = staging.open(stage.jobId(), stage.tenantId(), stage.fence());
        retry.accept(first); retry.finish();
        assertThat(staging.countForImport(stage.jobId())).isEqualTo(1);

        BulkLedgerStagingWriter.Session conflict = staging.open(stage.jobId(), stage.tenantId(), stage.fence());
        conflict.accept(row(stage, 2, "d"));
        assertThatThrownBy(conflict::finish).isInstanceOf(com.ticket.defender_core.application.service.BulkLedgerStagingConflictException.class);
        assertThat(staging.countForImport(stage.jobId())).isEqualTo(1);

        jdbc.update("update td_bulk_import_jobs set lease_until=utc_timestamp(6)-interval 1 second where id=?", stage.jobId().toString());
        BulkLedgerImportJob reclaimed = jobs.findById(stage.jobId()).orElseThrow();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        reclaimed.startValidation("new-worker", now.plusMinutes(5), now);
        reclaimed.beginStaging(reclaimed.getFenceToken(), now);
        jobs.saveAndFlush(reclaimed);

        BulkLedgerStagingWriter.Session stale = staging.open(stage.jobId(), stage.tenantId(), stage.fence());
        stale.accept(row(stage, 3, "e"));
        assertThatThrownBy(stale::finish).isInstanceOf(IllegalStateException.class).hasMessage("BULK_STAGING_FENCE_STALE");
        assertThat(staging.countForImport(stage.jobId())).isEqualTo(1);
        assertThat(jobs.findById(stage.jobId()).orElseThrow().getFenceToken()).isEqualTo(reclaimed.getFenceToken());
        assertThat(jobs.findById(stage.jobId()).orElseThrow().getStatus()).isEqualTo(BulkLedgerImportJob.Status.STAGING);

        BulkLedgerStagingWriter.Session resumed = staging.open(stage.jobId(), stage.tenantId(), reclaimed.getFenceToken());
        resumed.accept(row(stage, 3, "e")); resumed.finish();
        assertThat(staging.countForImport(stage.jobId())).isEqualTo(2);
    }

    private Stage stagingJob() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        UUID tenantId = UUID.randomUUID(), eventId = UUID.randomUUID(), membershipId = UUID.randomUUID(), performanceId = UUID.randomUUID();
        tenants.saveAndFlush(new Tenant(tenantId, "Synthetic staging tenant", AccessRecordStatus.ACTIVE));
        events.saveAndFlush(new TenantEvent(eventId, tenantId, "event-" + eventId, "Synthetic staging event", AccessRecordStatus.ACTIVE));
        memberships.saveAndFlush(new AdminMembershipEntity(membershipId, tenantId, "staging-" + membershipId, AccessRecordStatus.ACTIVE));
        performances.saveAndFlush(new VerifiedPerformance(performanceId, tenantId, eventId, "performance-" + performanceId,
                "synthetic", "v1", membershipId, now));
        BulkLedgerImportJob job = new BulkLedgerImportJob(tenantId, membershipId, "a".repeat(64), "b".repeat(64), "v1",
                "SYNTHETIC", "staging-" + UUID.randomUUID(), BulkLedgerImportJob.Mode.FULL_SNAPSHOT, BulkLedgerImportJob.Format.CSV,
                "c".repeat(64), 2, 2, true, now);
        job.beginUpload("d".repeat(64), now.plusMinutes(1), now);
        job.completeUpload("version-1", now);
        job.startValidation("old-worker", now.plusMinutes(5), now);
        job.beginStaging(job.getFenceToken(), now);
        jobs.saveAndFlush(job);
        return new Stage(job.getId(), tenantId, eventId, performanceId, job.getFenceToken());
    }

    private static BulkLedgerCsvStagingParser.ParsedRow row(Stage stage, long rowNumber, String digestSuffix) {
        return new BulkLedgerCsvStagingParser.ParsedRow(rowNumber, stage.eventId(), stage.performanceId(), "a".repeat(64),
                "b".repeat(64), null, "v1", LocalDateTime.of(2026, 11, 13, 10, 0), 1,
                null, null, null, digestSuffix.repeat(64));
    }

    private record Stage(UUID jobId, UUID tenantId, UUID eventId, UUID performanceId, long fence) { }
}
