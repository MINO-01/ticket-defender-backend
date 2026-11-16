package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.out.persistence.*;
import com.ticket.defender_core.application.service.BulkLedgerPublicationService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** BL-05 tests use only disposable CI MySQL and synthetic tenant registrations. */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "ticket-defender.track2-candidate-import.enabled=true",
        "ticket-defender.track2-candidate-import.operational-mode=SYNTHETIC_ISOLATED",
        "ticket-defender.track2-candidate-import.synthetic-isolation-id=" + BulkLedgerPublicationMySqlTest.ISOLATION,
        "ticket-defender.track2-candidate-import.reference-hmac-master-key=synthetic-only-master-key-material-that-is-long-enough",
        "ticket-defender.track2-candidate-import.reference-hmac-key-version=v1"
})
@EnabledIfEnvironmentVariable(named = "TD_ISSUE32_MYSQL_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/td_issue32_ci.*")
class BulkLedgerPublicationMySqlTest {
    static final String ISOLATION = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee";
    @Autowired private BulkLedgerPublicationService publications;
    @Autowired private BulkLedgerImportJobRepository jobs;
    @Autowired private BulkLedgerImportScopeRepository scopes;
    @Autowired private TenantRepository tenants;
    @Autowired private TenantEventRepository events;
    @Autowired private AdminMembershipRepository memberships;
    @Autowired private VerifiedPerformanceRepository performances;
    @Autowired private SyntheticReservationImportTenantRepository syntheticTenants;
    @Autowired private JdbcTemplate jdbc;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TD_ISSUE32_MYSQL_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("TD_ISSUE32_MYSQL_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("TD_ISSUE32_MYSQL_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Test
    void atomicallyPublishesAllScopesThenReplacesHeadAndPublishesChangeRevision() {
        Fixture fixture = fixture(2);
        Prepared initial = prepared(fixture, BulkLedgerImportJob.Mode.FULL_SNAPSHOT, List.of(
                scope(fixture, 0, null, 0, 0, "cursor-10", null, 10L, 10L),
                scope(fixture, 1, null, 0, 0, "cursor-10", null, 10L, 10L)));

        BulkLedgerPublicationService.Publication first = publish(initial, fixture);
        assertThat(first.scopes()).hasSize(2);
        assertThat(count("select count(*) from td_bulk_ledger_publication_heads where tenant_id=?", fixture.tenant())).isEqualTo(2);
        assertThat(count("select count(*) from td_bulk_ledger_publication_history where publication_id=?", first.publicationId())).isEqualTo(2);
        assertThat(count("select count(*) from td_bulk_ledger_publication_outbox where publication_id=?", first.publicationId())).isEqualTo(2);
        assertThat(value("select status from td_bulk_import_jobs where id=?", initial.job().getId())).isEqualTo("PUBLISHED");
        assertThat(publish(initial, fixture).publicationId()).isEqualTo(first.publicationId());

        UUID previous = versionFor(first, fixture.performances().get(0));
        Prepared replacement = prepared(fixture, BulkLedgerImportJob.Mode.FULL_SNAPSHOT, List.of(
                scope(fixture, 0, previous, 1, 0, "cursor-20", null, 20L, 20L)));
        BulkLedgerPublicationService.Publication second = publish(replacement, fixture);
        assertThat(second.scopes()).singleElement().satisfies(scope -> {
            assertThat(scope.previousVersionId()).isEqualTo(previous);
            assertThat(scope.generation()).isEqualTo(1);
            assertThat(scope.revision()).isZero();
        });

        UUID base = versionFor(second, fixture.performances().get(0));
        // CSV v2 snapshots intentionally carry as-of rather than a feed cursor. The published-feed baseline below is synthetic.
        jdbc.update("update td_bulk_ledger_versions set source_cursor='cursor-20',source_sequence_end=20 where id=?", base.toString());
        Prepared change = prepared(fixture, BulkLedgerImportJob.Mode.CHANGE_FEED, List.of(
                scope(fixture, 0, base, 1, 1, "cursor-21", "cursor-20", 21L, 21L)));
        BulkLedgerPublicationService.Publication revision = publish(change, fixture);
        assertThat(revision.scopes()).singleElement().satisfies(scope -> {
            assertThat(scope.previousVersionId()).isEqualTo(base);
            assertThat(scope.generation()).isEqualTo(1);
            assertThat(scope.revision()).isEqualTo(1);
        });
    }

    @Test
    void rejectsUnknownExpectedHeadWithoutChangingAnyScopeOrWritingOutbox() {
        Fixture fixture = fixture(2);
        Prepared invalid = prepared(fixture, BulkLedgerImportJob.Mode.FULL_SNAPSHOT, List.of(
                scope(fixture, 0, null, 0, 0, "cursor-10", null, 10L, 10L),
                scope(fixture, 1, null, 0, 0, "cursor-10", null, 10L, 10L)));
        jdbc.update("update td_bulk_ledger_versions set expected_head_known=0 where import_id=? and performance_id=?",
                invalid.job().getId().toString(), fixture.performances().get(1).toString());

        assertThatThrownBy(() -> publish(invalid, fixture)).hasMessageContaining("LEDGER_PUBLICATION_EXPECTED_HEAD_UNKNOWN");

        assertThat(count("select count(*) from td_bulk_ledger_publication_heads where tenant_id=?", fixture.tenant())).isZero();
        assertThat(count("select count(*) from td_bulk_ledger_publications where import_id=?", invalid.job().getId())).isZero();
        assertThat(count("select count(*) from td_bulk_ledger_publication_outbox where import_id=?", invalid.job().getId())).isZero();
        assertThat(value("select status from td_bulk_import_jobs where id=?", invalid.job().getId())).isEqualTo("READY_TO_PUBLISH");
    }

    @Test
    void concurrentImportsForTheSameFirstHeadAllowOnlyOnePublication() throws Exception {
        Fixture fixture = fixture(1);
        Prepared one = prepared(fixture, BulkLedgerImportJob.Mode.FULL_SNAPSHOT, List.of(scope(fixture, 0, null, 0, 0, "cursor-10", null, 10L, 10L)));
        Prepared two = prepared(fixture, BulkLedgerImportJob.Mode.FULL_SNAPSHOT, List.of(scope(fixture, 0, null, 1, 0, "cursor-11", null, 11L, 11L)));
        var pool = Executors.newFixedThreadPool(2);
        Future<Boolean> left = pool.submit(() -> tryPublish(one, fixture));
        Future<Boolean> right = pool.submit(() -> tryPublish(two, fixture));
        assertThat(List.of(left.get(), right.get())).containsExactlyInAnyOrder(true, false);
        pool.shutdown();
        assertThat(count("select count(*) from td_bulk_ledger_publication_heads where tenant_id=?", fixture.tenant())).isEqualTo(1);
        assertThat(count("select count(*) from td_bulk_ledger_publication_outbox where tenant_id=?", fixture.tenant())).isEqualTo(1);
    }

    @Test
    void outboxWriteFailureRollsBackHeadHistoryAndPublishedStates() {
        Fixture fixture = fixture(1);
        Prepared prepared = prepared(fixture, BulkLedgerImportJob.Mode.FULL_SNAPSHOT, List.of(scope(fixture, 0, null, 0, 0, "cursor-10", null, 10L, 10L)));
        jdbc.execute("create trigger td_bl05_outbox_fail before insert on td_bulk_ledger_publication_outbox for each row signal sqlstate '45000' set message_text='synthetic outbox failure'");
        try {
            assertThatThrownBy(() -> publish(prepared, fixture)).isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("drop trigger if exists td_bl05_outbox_fail");
        }
        assertThat(count("select count(*) from td_bulk_ledger_publication_heads where tenant_id=?", fixture.tenant())).isZero();
        assertThat(count("select count(*) from td_bulk_ledger_publication_history where import_id=?", prepared.job().getId())).isZero();
        assertThat(count("select count(*) from td_bulk_ledger_publication_outbox where import_id=?", prepared.job().getId())).isZero();
        assertThat(value("select status from td_bulk_import_jobs where id=?", prepared.job().getId())).isEqualTo("READY_TO_PUBLISH");
        assertThat(value("select status from td_bulk_ledger_candidate_imports where import_id=?", prepared.job().getId())).isEqualTo("READY");
    }

    private boolean tryPublish(Prepared prepared, Fixture fixture) {
        try { publish(prepared, fixture); return true; }
        catch (RuntimeException rejected) { return false; }
    }

    private BulkLedgerPublicationService.Publication publish(Prepared prepared, Fixture fixture) {
        return publications.publish(prepared.job().getId(), fixture.tenant(), fixture.member(), prepared.candidateId().toString());
    }

    private UUID versionFor(BulkLedgerPublicationService.Publication publication, UUID performanceId) {
        return publication.scopes().stream().filter(scope -> scope.performanceId().equals(performanceId)).findFirst().orElseThrow().versionId();
    }

    private Prepared prepared(Fixture fixture, BulkLedgerImportJob.Mode mode, List<CandidateScope> inputs) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).withNano(0);
        BulkLedgerImportJob job = new BulkLedgerImportJob(fixture.tenant(), fixture.member(), UUID.randomUUID().toString().replace("-", "") + "a".repeat(32), "b".repeat(64), "v2", "SYNTHETIC", "batch-" + UUID.randomUUID(), mode,
                BulkLedgerImportJob.Format.CSV, "c".repeat(64), 1, inputs.size(), mode == BulkLedgerImportJob.Mode.FULL_SNAPSHOT, now);
        job.beginUpload("d".repeat(64), now.plusMinutes(1), now); job.completeUpload("object-" + UUID.randomUUID(), now);
        job.startValidation("test", now.plusMinutes(1), now); job.beginStaging(job.getFenceToken(), now); job.readyToPublish(job.getFenceToken(), inputs.size(), inputs.size(), 0, now);
        jobs.saveAndFlush(job);
        UUID candidate = UUID.randomUUID();
        jdbc.update("insert into td_bulk_ledger_candidate_imports(id,import_id,tenant_id,source_object_version,source_object_sha256,status,preparation_attempt_count,preparation_fence_token,created_at,updated_at) values(?,?,?,?,?,'READY',0,0,utc_timestamp(6),utc_timestamp(6))",
                candidate.toString(), job.getId().toString(), fixture.tenant().toString(), job.getUploadObjectVersion(), job.getDeclaredObjectSha256());
        for (CandidateScope input : inputs) {
            String cursorStart = mode == BulkLedgerImportJob.Mode.FULL_SNAPSHOT ? null : input.cursorStart();
            String cursorEnd = mode == BulkLedgerImportJob.Mode.FULL_SNAPSHOT ? null : input.cursorEnd();
            Long sequenceStart = mode == BulkLedgerImportJob.Mode.FULL_SNAPSHOT ? null : input.sequenceStart();
            Long sequenceEnd = mode == BulkLedgerImportJob.Mode.FULL_SNAPSHOT ? null : input.sequenceEnd();
            scopes.saveAndFlush(new BulkLedgerImportScope(job.getId(), fixture.tenant(), fixture.event(), fixture.performances().get(input.performanceIndex()),
                    mode == BulkLedgerImportJob.Mode.FULL_SNAPSHOT ? now.plusSeconds(input.generation()) : null, 1,
                    cursorStart, cursorEnd, sequenceStart, sequenceEnd));
            jdbc.update("insert into td_bulk_ledger_versions(id,candidate_import_id,import_id,tenant_id,event_id,performance_id,generation_no,revision_no,base_version_id,expected_head_known,expected_head_version_id,source_system,source_batch_id,source_as_of,source_cursor,source_sequence_end,status,created_at) values(?,?,?,?,?,?,?,?,?,1,?,?,?,?,?,?, 'READY',utc_timestamp(6))",
                    UUID.randomUUID().toString(), candidate.toString(), job.getId().toString(), fixture.tenant().toString(), fixture.event().toString(), fixture.performances().get(input.performanceIndex()).toString(),
                    input.generation(), input.revision(), mode == BulkLedgerImportJob.Mode.FULL_SNAPSHOT || input.expectedHead() == null ? null : input.expectedHead().toString(),
                    input.expectedHead() == null ? null : input.expectedHead().toString(),
                    "SYNTHETIC", job.getSourceBatchId(), mode == BulkLedgerImportJob.Mode.FULL_SNAPSHOT ? java.sql.Timestamp.valueOf(now.plusSeconds(input.generation())) : null,
                    cursorEnd, sequenceEnd);
        }
        return new Prepared(job, candidate);
    }

    private CandidateScope scope(Fixture fixture, int performanceIndex, UUID expected, long generation, long revision,
                                 String cursorEnd, String cursorStart, Long sequenceEnd, Long sequenceStart) {
        return new CandidateScope(performanceIndex, expected, generation, revision, cursorEnd, cursorStart, sequenceEnd, sequenceStart);
    }

    private Fixture fixture(int performanceCount) {
        UUID tenant = UUID.randomUUID(), event = UUID.randomUUID(), member = UUID.randomUUID();
        tenants.saveAndFlush(new Tenant(tenant, "publication tenant", AccessRecordStatus.ACTIVE));
        events.saveAndFlush(new TenantEvent(event, tenant, "publication-" + event, "publication event", AccessRecordStatus.ACTIVE));
        memberships.saveAndFlush(new AdminMembershipEntity(member, tenant, "publication-" + member, AccessRecordStatus.ACTIVE));
        syntheticTenants.saveAndFlush(new SyntheticReservationImportTenant(tenant, UUID.fromString(ISOLATION), LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1)));
        List<UUID> performanceIds = new ArrayList<>();
        for (int i = 0; i < performanceCount; i++) {
            UUID id = UUID.randomUUID();
            performances.saveAndFlush(new VerifiedPerformance(id, tenant, event, "publication-" + id, "basis", "v1", member, LocalDateTime.now(ZoneOffset.UTC)));
            performanceIds.add(id);
        }
        return new Fixture(tenant, event, member, performanceIds);
    }

    private String value(String sql, UUID id) { return jdbc.queryForObject(sql, String.class, id.toString()); }
    private long count(String sql, UUID id) { return jdbc.queryForObject(sql, Long.class, id.toString()); }
    private record Fixture(UUID tenant, UUID event, UUID member, List<UUID> performances) { }
    private record Prepared(BulkLedgerImportJob job, UUID candidateId) { }
    private record CandidateScope(int performanceIndex, UUID expectedHead, long generation, long revision, String cursorEnd,
                                  String cursorStart, Long sequenceEnd, Long sequenceStart) { }
}
