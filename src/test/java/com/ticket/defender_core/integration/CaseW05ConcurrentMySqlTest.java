package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.in.web.dto.CaseDto;
import com.ticket.defender_core.adapter.in.web.dto.CasePreparationDto;
import com.ticket.defender_core.adapter.out.persistence.*;
import com.ticket.defender_core.application.service.CaseApiException;
import com.ticket.defender_core.application.service.CasePreparationService;
import com.ticket.defender_core.application.service.CaseService;
import com.ticket.defender_core.domain.*;
import com.ticket.defender_core.security.AdminRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs after W05 migration in the disposable MySQL 8 CI schema. */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@EnabledIfEnvironmentVariable(named = "TD_ISSUE32_MYSQL_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:13307/td_issue32.*")
class CaseW05ConcurrentMySqlTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID EVENT = UUID.fromString("33333333-3333-4333-8333-333333333333");

    @Autowired private CaseService casesService;
    @Autowired private CasePreparationService preparation;
    @Autowired private CaseRepository cases;
    @Autowired private EvidenceSnapshotRepository snapshots;
    @Autowired private ReportVersionRepository drafts;
    @Autowired private CaseActivityRepository activities;
    @Autowired private FanReportRepository reports;
    @Autowired private AdminMembershipRepository memberships;
    @Autowired private MembershipRoleRepository roles;
    @Autowired private MembershipEventGrantRepository grants;
    @Autowired private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TD_ISSUE32_MYSQL_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("TD_ISSUE32_MYSQL_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("TD_ISSUE32_MYSQL_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Test
    void simultaneousCaptureCreatesExactlyOneSnapshotAndActivity() throws Exception {
        Fixture fixture = reviewingCase();
        var input = new CasePreparationDto.Capture(fixture.reviewing().caseVersion(), "Capture synthetic source");
        List<String> outcomes = race(fixture.subject(), () -> preparation.capture(
                fixture.reviewing().caseId(), input, UUID.randomUUID().toString()).evidenceSnapshotId().toString());
        assertThat(outcomes.stream().filter(result -> result.startsWith("OK:"))).hasSize(1);
        assertThat(outcomes.stream().filter(result -> result.equals("CASE_VERSION_CONFLICT")
                || result.equals("SNAPSHOT_ALREADY_EXISTS"))).hasSize(1);
        assertThat(snapshots.findByCaseIdOrderByEvidenceRevisionDesc(fixture.reviewing().caseId())).hasSize(1);
        assertThat(activityCount(fixture.reviewing().caseId(), "EVIDENCE_SNAPSHOT_CAPTURED")).isEqualTo(1);
    }

    @Test
    void simultaneousSameKeyCaptureReplaysTheCommittedSnapshot() throws Exception {
        Fixture fixture = reviewingCase();
        String key = UUID.randomUUID().toString();
        var input = new CasePreparationDto.Capture(fixture.reviewing().caseVersion(), "Capture same source");
        List<String> outcomes = race(fixture.subject(), () -> preparation.capture(
                fixture.reviewing().caseId(), input, key).evidenceSnapshotId().toString());
        assertThat(outcomes).allMatch(result -> result.startsWith("OK:"));
        assertThat(outcomes.stream().distinct()).hasSize(1);
        assertThat(snapshots.findByCaseIdOrderByEvidenceRevisionDesc(fixture.reviewing().caseId())).hasSize(1);
        assertThat(activityCount(fixture.reviewing().caseId(), "EVIDENCE_SNAPSHOT_CAPTURED")).isEqualTo(1);
    }

    @Test
    void simultaneousDraftsAllocateOneRevisionWithoutDuplicateActivity() throws Exception {
        Fixture fixture = reviewingCase();
        var snapshot = preparation.capture(fixture.reviewing().caseId(),
                new CasePreparationDto.Capture(fixture.reviewing().caseVersion(), "Capture synthetic source"),
                UUID.randomUUID().toString());
        long expected = cases.findById(fixture.reviewing().caseId()).orElseThrow().getCaseVersion();
        List<String> outcomes = race(fixture.subject(), () -> preparation.draft(
                fixture.reviewing().caseId(), new CasePreparationDto.DraftInput(expected,
                        snapshot.evidenceSnapshotId(), "Synthetic title", "Synthetic assessment for reviewer",
                        "Metadata only", "Further review needed", "Prepare draft"),
                UUID.randomUUID().toString()).reportVersionId().toString());
        assertThat(outcomes.stream().filter(result -> result.startsWith("OK:"))).hasSize(1);
        assertThat(outcomes).contains("CASE_VERSION_CONFLICT");
        assertThat(drafts.findByCaseIdOrderByRevisionDesc(fixture.reviewing().caseId())).hasSize(1);
        assertThat(drafts.latestRevision(fixture.reviewing().caseId())).isEqualTo(1);
        assertThat(activityCount(fixture.reviewing().caseId(), "REPORT_DRAFT_CREATED")).isEqualTo(1);
    }

    @Test
    void simultaneousSameKeyDraftReplaysTheCommittedRevision() throws Exception {
        Fixture fixture = reviewingCase();
        UUID caseId = fixture.reviewing().caseId();
        var snapshot = preparation.capture(caseId,
                new CasePreparationDto.Capture(fixture.reviewing().caseVersion(), "Capture source"),
                UUID.randomUUID().toString());
        long expected = cases.findById(caseId).orElseThrow().getCaseVersion();
        var input = new CasePreparationDto.DraftInput(expected, snapshot.evidenceSnapshotId(),
                "Synthetic title", "Synthetic assessment for reviewer", "Metadata only",
                "Further review needed", "Prepare same draft");
        String key = UUID.randomUUID().toString();
        List<String> outcomes = race(fixture.subject(), () -> preparation.draft(caseId, input, key)
                .reportVersionId().toString());
        assertThat(outcomes).allMatch(result -> result.startsWith("OK:"));
        assertThat(outcomes.stream().distinct()).hasSize(1);
        assertThat(drafts.findByCaseIdOrderByRevisionDesc(caseId)).hasSize(1);
        assertThat(activityCount(caseId, "REPORT_DRAFT_CREATED")).isEqualTo(1);
    }

    @Test
    void activityFailureRollsBackSnapshotAndCaseVersion() {
        Fixture fixture = reviewingCase();
        UUID caseId = fixture.reviewing().caseId();
        String trigger = "td_w05_activity_failure";
        jdbc.execute("DROP TRIGGER IF EXISTS " + trigger);
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON case_activities FOR EACH ROW "
                + "BEGIN IF NEW.case_id = '" + caseId + "' "
                + "AND NEW.event_type = 'EVIDENCE_SNAPSHOT_CAPTURED' THEN "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'synthetic activity failure'; "
                + "END IF; END");
        try {
            assertThatThrownBy(() -> preparation.capture(caseId,
                    new CasePreparationDto.Capture(fixture.reviewing().caseVersion(), "Capture source"),
                    UUID.randomUUID().toString())).isInstanceOf(RuntimeException.class);
            assertThat(snapshots.findByCaseIdOrderByEvidenceRevisionDesc(caseId)).isEmpty();
            assertThat(cases.findById(caseId).orElseThrow().getCaseVersion())
                    .isEqualTo(fixture.reviewing().caseVersion());
            assertThat(activityCount(caseId, "EVIDENCE_SNAPSHOT_CAPTURED")).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS " + trigger);
        }
    }

    @Test
    void activityFailureRollsBackDraftAndCaseVersion() {
        Fixture fixture = reviewingCase();
        UUID caseId = fixture.reviewing().caseId();
        var snapshot = preparation.capture(caseId,
                new CasePreparationDto.Capture(fixture.reviewing().caseVersion(), "Capture source"),
                UUID.randomUUID().toString());
        long before = cases.findById(caseId).orElseThrow().getCaseVersion();
        String trigger = "td_w05_draft_activity_failure";
        jdbc.execute("DROP TRIGGER IF EXISTS " + trigger);
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON case_activities FOR EACH ROW "
                + "BEGIN IF NEW.case_id = '" + caseId + "' "
                + "AND NEW.event_type = 'REPORT_DRAFT_CREATED' THEN "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'synthetic draft audit failure'; "
                + "END IF; END");
        try {
            assertThatThrownBy(() -> preparation.draft(caseId, new CasePreparationDto.DraftInput(
                    before, snapshot.evidenceSnapshotId(), "Synthetic title",
                    "Synthetic assessment for reviewer", "Metadata only", "Further review needed",
                    "Prepare draft"), UUID.randomUUID().toString())).isInstanceOf(RuntimeException.class);
            assertThat(drafts.findByCaseIdOrderByRevisionDesc(caseId)).isEmpty();
            assertThat(cases.findById(caseId).orElseThrow().getCaseVersion()).isEqualTo(before);
            assertThat(activityCount(caseId, "REPORT_DRAFT_CREATED")).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS " + trigger);
        }
    }

    private long activityCount(UUID caseId, String type) {
        return activities.findByCaseId(caseId, org.springframework.data.domain.Pageable.unpaged()).stream()
                .filter(activity -> activity.getEventType().equals(type)).count();
    }

    private Fixture reviewingCase() {
        UUID memberId = UUID.randomUUID();
        String subject = "w05-reviewer-" + memberId;
        memberships.saveAndFlush(new AdminMembershipEntity(memberId, TENANT, subject, AccessRecordStatus.ACTIVE));
        roles.saveAndFlush(new MembershipRole(UUID.randomUUID(), memberId, AdminRole.REVIEWER));
        grants.saveAndFlush(new MembershipEventGrant(UUID.randomUUID(), memberId, TENANT, EVENT));
        UUID reportId = UUID.randomUUID();
        FanReport report = new FanReport(reportId, "FR-" + reportId, TENANT, EVENT,
                LocalDateTime.now(ZoneOffset.UTC).plusDays(1), null, FanReport.ReportType.OTHER,
                "Synthetic report", null, null, null, null, LocalDateTime.now(ZoneOffset.UTC),
                UUID.randomUUID().toString(), "d".repeat(64), "e".repeat(64));
        report.markReceived();
        reports.saveAndFlush(report);
        authenticate(subject);
        CaseDto.View opened = casesService.create(new CaseDto.Create(EVENT, Case.Type.FAN_REPORT,
                List.of(), List.of(reportId), List.of(), "Investigate synthetic report", null, null),
                UUID.randomUUID().toString());
        CaseDto.View assigned = casesService.assign(opened.caseId(),
                new CaseDto.Assign(memberId, opened.caseVersion(), "Assign reviewer"), UUID.randomUUID().toString());
        CaseDto.View triaged = casesService.transition(opened.caseId(),
                new CaseDto.Transition(Case.Status.TRIAGED, assigned.caseVersion(), "Triage case",
                        "Synthetic classification", null, null, null, null, null), UUID.randomUUID().toString());
        CaseDto.View reviewing = casesService.transition(opened.caseId(),
                new CaseDto.Transition(Case.Status.IN_REVIEW, triaged.caseVersion(), "Begin review",
                        null, null, null, null, null, null), UUID.randomUUID().toString());
        return new Fixture(reviewing, subject);
    }

    private List<String> race(String subject, Action action) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean run = new AtomicBoolean();
        List<Future<String>> results = new ArrayList<>();
        var workers = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon(true).factory());
        try {
            for (int i = 0; i < 2; i++) {
                results.add(workers.submit(() -> {
                    try {
                        authenticate(subject);
                        ready.countDown();
                        start.await();
                        if (!run.get()) return "ABORTED";
                        return "OK:" + action.execute();
                    } catch (CaseApiException conflict) {
                        return conflict.code();
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                }));
            }
            awaitReadyAndRelease(ready, start, run, 5, TimeUnit.SECONDS);
            List<String> outcomes = new ArrayList<>();
            for (Future<String> result : results) outcomes.add(result.get(30, TimeUnit.SECONDS));
            return outcomes;
        } finally {
            start.countDown();
            results.forEach(result -> result.cancel(true));
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    static void awaitReadyAndRelease(CountDownLatch ready, CountDownLatch start,
            AtomicBoolean run, long timeout, TimeUnit unit) throws InterruptedException {
        try {
            assertThat(ready.await(timeout, unit)).isTrue();
            run.set(true);
        } finally {
            start.countDown();
        }
    }

    private static void authenticate(String subject) {
        Jwt jwt = Jwt.withTokenValue("synthetic").header("alg", "RS256").subject(subject).build();
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt);
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private record Fixture(CaseDto.View reviewing, String subject) { }

    private interface Action { String execute(); }
}
