package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.in.web.dto.CaseDto;
import com.ticket.defender_core.adapter.in.web.dto.CasePreparationDto;
import com.ticket.defender_core.adapter.in.web.dto.EvidenceIntegrityDto;
import com.ticket.defender_core.adapter.out.persistence.*;
import com.ticket.defender_core.application.service.CaseApiException;
import com.ticket.defender_core.application.service.CasePreparationService;
import com.ticket.defender_core.application.service.CaseService;
import com.ticket.defender_core.application.service.EvidenceIntegrityService;
import com.ticket.defender_core.application.service.EvidenceObjectStorage;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

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
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@EnabledIfEnvironmentVariable(named = "TD_ISSUE32_MYSQL_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:13307/td_issue32.*")
class CaseW06ConcurrentMySqlTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID EVENT = UUID.fromString("33333333-3333-4333-8333-333333333333");

    @Autowired private CaseService casesService;
    @Autowired private CasePreparationService preparation;
    @Autowired private EvidenceIntegrityService integrity;
    @Autowired private CaseRepository cases;
    @Autowired private CaseSourceReferenceRepository sources;
    @Autowired private EvidenceVerificationRecordRepository records;
    @Autowired private CaseActivityRepository activities;
    @Autowired private CaseIdempotencyRepository idempotency;
    @Autowired private FanReportRepository reports;
    @Autowired private EvidenceAssetRepository assets;
    @Autowired private AdminMembershipRepository memberships;
    @Autowired private MembershipRoleRepository roles;
    @Autowired private MembershipEventGrantRepository grants;
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
    void sameKeyConcurrentVerificationCommitsOneRecordAndActivity() throws Exception {
        Fixture f = fixture();
        String key = UUID.randomUUID().toString();
        var input = input(f);
        List<String> outcomes = race(f.subject(), () -> integrity.verify(f.caseId(), input, key)
                .recordId().toString());
        assertThat(outcomes).allMatch(result -> result.startsWith("OK:"));
        assertThat(outcomes.stream().distinct()).hasSize(1);
        assertThat(records.findByCaseId(f.caseId(), org.springframework.data.domain.Pageable.unpaged())).hasSize(1);
        assertThat(activityCount(f.caseId())).isEqualTo(1);
    }

    @Test
    void distinctKeysAtSameCaseVersionCannotBothCommit() throws Exception {
        Fixture f = fixture();
        var input = input(f);
        List<String> outcomes = race(f.subject(), () -> integrity.verify(f.caseId(), input,
                UUID.randomUUID().toString()).recordId().toString());
        assertThat(outcomes.stream().filter(result -> result.startsWith("OK:"))).hasSize(1);
        assertThat(outcomes).contains("CASE_VERSION_CONFLICT");
        assertThat(records.findByCaseId(f.caseId(), org.springframework.data.domain.Pageable.unpaged())).hasSize(1);
        assertThat(activityCount(f.caseId())).isEqualTo(1);
    }

    @Test
    void failedActivityInsertRollsBackVerificationVersionAndIdempotency() {
        Fixture f = fixture();
        long before = cases.findById(f.caseId()).orElseThrow().getCaseVersion();
        String trigger = "td_w06_activity_failure";
        jdbc.execute("DROP TRIGGER IF EXISTS " + trigger);
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON case_activities FOR EACH ROW "
                + "BEGIN IF NEW.case_id = '" + f.caseId() + "' "
                + "AND NEW.event_type = 'EVIDENCE_BYTES_CHECKED' THEN "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'synthetic activity failure'; "
                + "END IF; END");
        String key = UUID.randomUUID().toString();
        try {
            assertThatThrownBy(() -> integrity.verify(f.caseId(), input(f), key))
                    .isInstanceOf(RuntimeException.class);
            assertThat(records.findByCaseId(f.caseId(), org.springframework.data.domain.Pageable.unpaged())).isEmpty();
            assertThat(activityCount(f.caseId())).isZero();
            assertThat(cases.findById(f.caseId()).orElseThrow().getCaseVersion()).isEqualTo(before);
            assertThat(idempotency.findByTenantIdAndActorMembershipIdAndOperationAndTargetCaseIdAndKeyDigest(
                    TENANT, f.memberId(), "EVIDENCE_VERIFY", f.caseId(),
                    com.ticket.defender_core.application.service.CaseSnapshotManifest.sha256(key))).isEmpty();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS " + trigger);
        }
    }

    private long activityCount(UUID caseId) {
        return activities.findByCaseId(caseId, org.springframework.data.domain.Pageable.unpaged())
                .stream().filter(activity -> activity.getEventType().equals("EVIDENCE_BYTES_CHECKED")).count();
    }

    private Fixture fixture() {
        UUID memberId = UUID.randomUUID();
        String subject = "w06-reviewer-" + memberId;
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
        UUID evidenceId = UUID.randomUUID();
        String key = "fan-reports/" + reportId + "/" + evidenceId;
        EvidenceAsset asset = new EvidenceAsset(evidenceId, reportId, key, "image/png", 4,
                "a".repeat(64), LocalDateTime.now(ZoneOffset.UTC), report.getRetentionUntil());
        asset.markStored("etag");
        assets.saveAndFlush(asset);
        when(storage.head(key)).thenReturn(new EvidenceObjectStorage.ObjectMetadata(4, "image/png", "etag"));
        when(storage.verifyBytes(key, "etag", 20L * 1024 * 1024)).thenReturn(
                new EvidenceObjectStorage.ByteVerification(4, "image/png", "a".repeat(64),
                        "etag", EvidenceObjectStorage.MarkerKind.ETAG));
        authenticate(subject);
        CaseDto.View opened = casesService.create(new CaseDto.Create(EVENT, Case.Type.FAN_REPORT,
                List.of(), List.of(reportId), List.of(evidenceId), "Investigate synthetic report", null, null),
                UUID.randomUUID().toString());
        CaseDto.View assigned = casesService.assign(opened.caseId(),
                new CaseDto.Assign(memberId, opened.caseVersion(), "Assign reviewer"), UUID.randomUUID().toString());
        CaseDto.View triaged = casesService.transition(opened.caseId(),
                new CaseDto.Transition(Case.Status.TRIAGED, assigned.caseVersion(), "Triage case",
                        "Synthetic classification", null, null, null, null, null), UUID.randomUUID().toString());
        CaseDto.View reviewing = casesService.transition(opened.caseId(),
                new CaseDto.Transition(Case.Status.IN_REVIEW, triaged.caseVersion(), "Begin review",
                        null, null, null, null, null, null), UUID.randomUUID().toString());
        var snapshot = preparation.capture(reviewing.caseId(),
                new CasePreparationDto.Capture(reviewing.caseVersion(), "Capture stored source"),
                UUID.randomUUID().toString());
        UUID sourceId = sources.findByCaseIdOrderByLinkedAtAscIdAsc(reviewing.caseId()).stream()
                .filter(ref -> ref.getSourceType() == CaseSourceReference.Type.EVIDENCE_ASSET)
                .findFirst().orElseThrow().getId();
        return new Fixture(reviewing.caseId(), snapshot.evidenceSnapshotId(), sourceId,
                evidenceId, subject, memberId);
    }

    private EvidenceIntegrityDto.VerifyInput input(Fixture f) {
        return new EvidenceIntegrityDto.VerifyInput(cases.findById(f.caseId()).orElseThrow().getCaseVersion(),
                f.snapshotId(), f.sourceId(), f.evidenceId(), "Check synthetic bytes");
    }

    private static List<String> race(String subject, Action action) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean run = new AtomicBoolean();
        List<Future<String>> futures = new ArrayList<>();
        var workers = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon(true).factory());
        try {
            for (int i = 0; i < 2; i++) futures.add(workers.submit(() -> {
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
            try {
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                run.set(true);
            } finally {
                start.countDown();
            }
            List<String> results = new ArrayList<>();
            for (Future<String> future : futures) results.add(future.get(30, TimeUnit.SECONDS));
            return results;
        } finally {
            start.countDown();
            futures.forEach(future -> future.cancel(true));
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void authenticate(String subject) {
        JwtAuthenticationToken auth = new JwtAuthenticationToken(
                Jwt.withTokenValue("synthetic").header("alg", "RS256").subject(subject).build());
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private record Fixture(UUID caseId, UUID snapshotId, UUID sourceId, UUID evidenceId,
                           String subject, UUID memberId) { }
    private interface Action { String execute(); }
}
