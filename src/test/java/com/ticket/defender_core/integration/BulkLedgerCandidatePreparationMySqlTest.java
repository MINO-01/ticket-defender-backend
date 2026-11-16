package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.out.persistence.*;
import com.ticket.defender_core.application.service.BulkLedgerCandidatePreparationService;
import com.ticket.defender_core.domain.*;
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

/** Candidate generation/revision checks against the disposable MySQL 8 CI schema. Publication rows below are fixtures only. */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@EnabledIfEnvironmentVariable(named = "TD_ISSUE32_MYSQL_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/td_issue32_ci.*")
class BulkLedgerCandidatePreparationMySqlTest {
    @Autowired private BulkLedgerCandidatePreparationService candidates;
    @Autowired private BulkLedgerImportJobRepository jobs;
    @Autowired private BulkLedgerImportScopeRepository scopes;
    @Autowired private TenantRepository tenants;
    @Autowired private TenantEventRepository events;
    @Autowired private AdminMembershipRepository memberships;
    @Autowired private VerifiedPerformanceRepository performances;
    @Autowired private JdbcTemplate jdbc;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TD_ISSUE32_MYSQL_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("TD_ISSUE32_MYSQL_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("TD_ISSUE32_MYSQL_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Test
    void fullSnapshotCreatesReadyGenerationZeroAndRetryDoesNotDuplicateCandidate() {
        long existingTrack1Rows = count("select count(*) from track1_purchase_ledger_rows");
        Fixture fixture = fixture();
        BulkLedgerImportJob job = ready(fixture, BulkLedgerImportJob.Mode.FULL_SNAPSHOT, "snapshot", "cursor-10", null, 10L, 1);
        stage(job, fixture, "ticket-a", "event-a", null, "ACTIVE");

        candidates.prepare(job.getId()); candidates.prepare(job.getId());

        assertThat(value("select status from td_bulk_ledger_candidate_imports where import_id=?", job.getId())).isEqualTo("READY");
        assertThat(value("select generation_no from td_bulk_ledger_versions where import_id=?", job.getId())).isEqualTo("0");
        assertThat(value("select revision_no from td_bulk_ledger_versions where import_id=?", job.getId())).isEqualTo("0");
        assertThat(value("select expected_head_known from td_bulk_ledger_versions where import_id=?", job.getId())).isEqualTo("1");
        assertThat(count("select count(*) from td_bulk_ledger_ticket_versions tv join td_bulk_ledger_versions v on v.id=tv.version_id where v.import_id=?", job.getId())).isEqualTo(1);
        assertThat(count("select count(*) from td_bulk_ledger_versions where import_id=?", job.getId())).isEqualTo(1);
        assertThat(count("select count(*) from track1_purchase_ledger_rows")).isEqualTo(existingTrack1Rows);
    }

    @Test
    void expiredPreparingCandidateResumesExistingVersionAndTicketsWithoutRestaging() {
        Fixture fixture = fixture();
        BulkLedgerImportJob job = ready(fixture, BulkLedgerImportJob.Mode.FULL_SNAPSHOT, "resume", "cursor-10", null, 10L, 1);
        stage(job, fixture, "ticket-a", "event-a", null, "ACTIVE");
        candidates.prepare(job.getId());

        jdbc.update("update td_bulk_ledger_candidate_imports set status='PREPARING', preparation_owner='interrupted-worker', preparation_lease_until=date_add(utc_timestamp(6), interval 1 minute), last_retry_code='CANDIDATE_PREPARATION_RETRYABLE' where import_id=?", job.getId().toString());
        jdbc.update("update td_bulk_ledger_versions set status='PREPARING' where import_id=?", job.getId().toString());
        candidates.prepare(job.getId());
        assertThat(value("select status from td_bulk_ledger_candidate_imports where import_id=?", job.getId())).isEqualTo("PREPARING");
        jdbc.update("update td_bulk_ledger_candidate_imports set preparation_lease_until=date_sub(utc_timestamp(6), interval 1 second) where import_id=?", job.getId().toString());
        candidates.prepare(job.getId());

        assertThat(value("select status from td_bulk_ledger_candidate_imports where import_id=?", job.getId())).isEqualTo("READY");
        assertThat(count("select count(*) from td_bulk_ledger_versions where import_id=?", job.getId())).isEqualTo(1);
        assertThat(count("select count(*) from td_bulk_ledger_ticket_versions tv join td_bulk_ledger_versions v on v.id=tv.version_id where v.import_id=?", job.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select preparation_attempt_count from td_bulk_ledger_candidate_imports where import_id=?", Integer.class, job.getId().toString())).isEqualTo(2);
    }

    @Test
    void changeFeedRequiresPublicationFixtureThenCreatesRevisionAndKeepsOnlyChangedTicketRows() {
        Fixture fixture = fixture();
        BulkLedgerImportJob snapshot = ready(fixture, BulkLedgerImportJob.Mode.FULL_SNAPSHOT, "base", "cursor-10", null, 10L, 1);
        stage(snapshot, fixture, "ticket-a", "base-event", null, "ACTIVE"); candidates.prepare(snapshot.getId());
        publish(fixture, snapshot.getId(), "cursor-10", 10L);

        BulkLedgerImportJob change = ready(fixture, BulkLedgerImportJob.Mode.CHANGE_FEED, "change", "cursor-12", "cursor-10", 12L, 2);
        stage(change, fixture, "ticket-a", "change-11", 11L, "ACTIVE");
        stage(change, fixture, "ticket-a", "change-12", 12L, "CANCELLED");
        candidates.prepare(change.getId());

        assertThat(value("select status from td_bulk_ledger_candidate_imports where import_id=?", change.getId())).isEqualTo("READY");
        assertThat(value("select revision_no from td_bulk_ledger_versions where import_id=?", change.getId())).isEqualTo("1");
        assertThat(count("select count(*) from td_bulk_ledger_ticket_versions tv join td_bulk_ledger_versions v on v.id=tv.version_id where v.import_id=?", change.getId())).isEqualTo(1);
        assertThat(value("select ticket_status from td_bulk_ledger_ticket_versions tv join td_bulk_ledger_versions v on v.id=tv.version_id where v.import_id=?", change.getId())).isEqualTo("CANCELLED");
        assertThat(count("select count(*) from td_bulk_ledger_change_events ce join td_bulk_ledger_versions v on v.id=ce.version_id where v.import_id=?", change.getId())).isEqualTo(2);
    }

    @Test
    void changeFeedWithoutPublishedHeadIsRejectedAndNeverBecomesReady() {
        Fixture fixture = fixture();
        BulkLedgerImportJob change = ready(fixture, BulkLedgerImportJob.Mode.CHANGE_FEED, "no-head", "cursor-11", "cursor-10", 11L, 1);
        stage(change, fixture, "ticket-a", "event-11", 11L, "ACTIVE");
        candidates.prepare(change.getId());
        assertThat(value("select status from td_bulk_ledger_candidate_imports where import_id=?", change.getId())).isEqualTo("REJECTED");
        assertThat(value("select failure_code from td_bulk_ledger_candidate_imports where import_id=?", change.getId())).isEqualTo("CHANGE_BASELINE_NOT_PUBLISHED");
        assertThat(count("select count(*) from td_bulk_ledger_versions where import_id=?", change.getId())).isZero();
    }

    @Test
    void changeFeedRejectsSequenceGapAndConflictingReusedEvent() {
        Fixture fixture = fixture();
        BulkLedgerImportJob snapshot = ready(fixture, BulkLedgerImportJob.Mode.FULL_SNAPSHOT, "base", "cursor-10", null, 10L, 1);
        stage(snapshot, fixture, "ticket-a", "base", null, "ACTIVE"); candidates.prepare(snapshot.getId()); publish(fixture, snapshot.getId(), "cursor-10", 10L);

        BulkLedgerImportJob gap = ready(fixture, BulkLedgerImportJob.Mode.CHANGE_FEED, "gap", "cursor-11", "cursor-10", 11L, 1);
        stage(gap, fixture, "ticket-a", "gap-event", 12L, "ACTIVE"); candidates.prepare(gap.getId());
        assertThat(value("select failure_code from td_bulk_ledger_candidate_imports where import_id=?", gap.getId())).isEqualTo("CHANGE_SEQUENCE_INVALID");

        BulkLedgerImportJob first = ready(fixture, BulkLedgerImportJob.Mode.CHANGE_FEED, "first", "cursor-11", "cursor-10", 11L, 1);
        stage(first, fixture, "ticket-a", "same-event", 11L, "ACTIVE"); candidates.prepare(first.getId()); publish(fixture, first.getId(), null, null);
        BulkLedgerImportJob conflict = ready(fixture, BulkLedgerImportJob.Mode.CHANGE_FEED, "conflict", "cursor-12", "cursor-11", 12L, 1);
        stage(conflict, fixture, "ticket-a", "same-event", 12L, "CANCELLED");
        jdbc.update("update td_bulk_import_staging_rows set row_digest=? where import_id=?", "f".repeat(64), conflict.getId().toString());
        candidates.prepare(conflict.getId());
        assertThat(value("select failure_code from td_bulk_ledger_candidate_imports where import_id=?", conflict.getId())).isEqualTo("CHANGE_EVENT_CONFLICT");
    }

    @Test
    void rejectedMultiScopeCandidateReleasesOnlyItsPrivateEventsForANewImport() {
        Fixture first = fixture();
        Fixture second = laterPerformance(first);
        BulkLedgerImportJob baseA = ready(first, BulkLedgerImportJob.Mode.FULL_SNAPSHOT, "base-a", "cursor-10", null, 10L, 1);
        stage(baseA, first, "ticket-a", "base-a", null, "ACTIVE"); candidates.prepare(baseA.getId()); publish(first, baseA.getId(), "cursor-10", 10L);
        BulkLedgerImportJob baseB = ready(second, BulkLedgerImportJob.Mode.FULL_SNAPSHOT, "base-b", "cursor-10", null, 10L, 1);
        stage(baseB, second, "ticket-b", "base-b", null, "ACTIVE"); candidates.prepare(baseB.getId()); publish(second, baseB.getId(), "cursor-10", 10L);

        BulkLedgerImportJob rejected = ready(first, BulkLedgerImportJob.Mode.CHANGE_FEED, "two-scopes", "cursor-11", "cursor-10", 11L, 2, 1);
        scopes.saveAndFlush(new BulkLedgerImportScope(rejected.getId(), first.tenant(), first.event(), second.performance(), null, 1,
                "cursor-10", "cursor-11", 11L, 11L));
        stage(rejected, first, "ticket-a", "reusable-event", 11L, "ACTIVE");
        stage(rejected, second, "ticket-b", "blocked-event", 11L, "ACTIVE");
        jdbc.update("update td_bulk_import_staging_rows set change_action='REMOVE' where import_id=? and performance_id=?", rejected.getId().toString(), second.performance().toString());
        candidates.prepare(rejected.getId());

        assertThat(value("select status from td_bulk_ledger_candidate_imports where import_id=?", rejected.getId())).isEqualTo("REJECTED");
        assertThat(count("select count(*) from td_bulk_ledger_change_events ce join td_bulk_ledger_versions v on v.id=ce.version_id where v.import_id=?", rejected.getId())).isZero();

        BulkLedgerImportJob retry = ready(first, BulkLedgerImportJob.Mode.CHANGE_FEED, "retry-a", "cursor-11", "cursor-10", 11L, 1);
        stage(retry, first, "ticket-a", "reusable-event", 11L, "ACTIVE");
        candidates.prepare(retry.getId());
        assertThat(value("select status from td_bulk_ledger_candidate_imports where import_id=?", retry.getId())).isEqualTo("READY");
        assertThat(count("select count(*) from td_bulk_ledger_change_events ce join td_bulk_ledger_versions v on v.id=ce.version_id where v.import_id=?", retry.getId())).isEqualTo(1);
    }

    private Fixture fixture() {
        UUID tenant = UUID.randomUUID(), event = UUID.randomUUID(), member = UUID.randomUUID(), performance = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        tenants.saveAndFlush(new Tenant(tenant, "candidate tenant", AccessRecordStatus.ACTIVE));
        events.saveAndFlush(new TenantEvent(event, tenant, "candidate-" + event, "candidate event", AccessRecordStatus.ACTIVE));
        memberships.saveAndFlush(new AdminMembershipEntity(member, tenant, "candidate-" + member, AccessRecordStatus.ACTIVE));
        performances.saveAndFlush(new VerifiedPerformance(performance, tenant, event, "candidate-" + performance, "basis", "v1", member, now));
        return new Fixture(tenant, event, member, performance);
    }
    private Fixture laterPerformance(Fixture first) {
        UUID performance;
        do { performance = UUID.randomUUID(); } while (performance.toString().compareTo(first.performance().toString()) <= 0);
        performances.saveAndFlush(new VerifiedPerformance(performance, first.tenant(), first.event(), "candidate-" + performance,
                "basis", "v1", first.member(), LocalDateTime.now(ZoneOffset.UTC)));
        return new Fixture(first.tenant(), first.event(), first.member(), performance);
    }
    private BulkLedgerImportJob ready(Fixture f, BulkLedgerImportJob.Mode mode, String batch, String cursorEnd, String cursorStart, long sequenceEnd, int rows) {
        return ready(f, mode, batch, cursorEnd, cursorStart, sequenceEnd, rows, rows);
    }
    private BulkLedgerImportJob ready(Fixture f, BulkLedgerImportJob.Mode mode, String batch, String cursorEnd, String cursorStart, long sequenceEnd, int jobRows, int scopeRows) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        BulkLedgerImportJob job = new BulkLedgerImportJob(f.tenant(), f.member(), UUID.randomUUID().toString().replace("-", "") + "a".repeat(32), "b".repeat(64), "v2", "SYNTHETIC", batch + UUID.randomUUID(), mode, BulkLedgerImportJob.Format.CSV, "c".repeat(64), 1, jobRows, mode == BulkLedgerImportJob.Mode.FULL_SNAPSHOT, now);
        job.beginUpload("d".repeat(64), now.plusMinutes(1), now); job.completeUpload("version", now); job.startValidation("candidate-test", now.plusMinutes(1), now); job.beginStaging(job.getFenceToken(), now); job.readyToPublish(job.getFenceToken(), jobRows, jobRows, 0, now);
        jobs.saveAndFlush(job);
        scopes.saveAndFlush(new BulkLedgerImportScope(job.getId(), f.tenant(), f.event(), f.performance(),
                mode == BulkLedgerImportJob.Mode.FULL_SNAPSHOT ? now : null, scopeRows,
                mode == BulkLedgerImportJob.Mode.CHANGE_FEED ? cursorStart : null,
                mode == BulkLedgerImportJob.Mode.CHANGE_FEED ? cursorEnd : null,
                mode == BulkLedgerImportJob.Mode.CHANGE_FEED ? sequenceEnd - scopeRows + 1 : null,
                mode == BulkLedgerImportJob.Mode.CHANGE_FEED ? sequenceEnd : null));
        return job;
    }
    private void stage(BulkLedgerImportJob job, Fixture f, String ticket, String eventId, Long sequence, String status) {
        long row = jdbc.queryForObject("select count(*) from td_bulk_import_staging_rows where import_id=?", Long.class, job.getId().toString()) + 2;
        jdbc.update("insert into td_bulk_import_staging_rows(id,import_id,tenant_id,event_id,performance_id,source_row_no,reservation_reference_hmac,ticket_reference_hmac,account_reference_hmac,hmac_key_version,purchased_at,ticket_quantity,quantity_unit,ticket_status,source_version,source_event_id,source_changed_at,source_sequence,change_action,row_digest) values(?,?,?,?,?,?,?,?,?,?,utc_timestamp(6),1,'TICKET',?,'v-source',?,utc_timestamp(6),?,?,?)",
                UUID.randomUUID().toString(), job.getId().toString(), f.tenant().toString(), f.event().toString(), f.performance().toString(), row,
                "r".repeat(63) + row % 10, (ticket + "x".repeat(64)).substring(0, 64), "a".repeat(64), "v1", status,
                job.getMode() == BulkLedgerImportJob.Mode.CHANGE_FEED ? eventId : null, sequence,
                job.getMode() == BulkLedgerImportJob.Mode.CHANGE_FEED ? "UPSERT" : null, (eventId + "d".repeat(64)).substring(0, 64));
    }
    private void publish(Fixture fixture, UUID importId, String cursor, Long sequenceEnd) {
        String version = value("select id from td_bulk_ledger_versions where import_id=?", importId);
        if (cursor != null) jdbc.update("update td_bulk_ledger_versions set source_cursor=?, source_sequence_end=? where id=?", cursor, sequenceEnd, version);
        jdbc.update("update td_bulk_ledger_versions set status='PUBLISHED' where id=?", version);
        int updated = jdbc.update("update td_bulk_ledger_publication_heads set version_id=?, published_at=utc_timestamp(6) where tenant_id=? and performance_id=?",
                version, fixture.tenant().toString(), fixture.performance().toString());
        if (updated == 0) jdbc.update("insert into td_bulk_ledger_publication_heads(tenant_id,event_id,performance_id,version_id,published_at) values(?,?,?,?,utc_timestamp(6))",
                fixture.tenant().toString(), fixture.event().toString(), fixture.performance().toString(), version);
    }
    private String value(String sql, UUID id) { return jdbc.queryForObject(sql, String.class, id.toString()); }
    private long count(String sql, UUID id) { return jdbc.queryForObject(sql, Long.class, id.toString()); }
    private long count(String sql) { return jdbc.queryForObject(sql, Long.class); }
    private record Fixture(UUID tenant, UUID event, UUID member, UUID performance) { }
}
