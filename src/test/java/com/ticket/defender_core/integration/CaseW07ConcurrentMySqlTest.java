package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.in.web.dto.CaseDto;
import com.ticket.defender_core.adapter.in.web.dto.CasePreparationDto;
import com.ticket.defender_core.adapter.in.web.dto.EvidenceIntegrityDto;
import com.ticket.defender_core.adapter.out.persistence.*;
import com.ticket.defender_core.application.service.*;
import com.ticket.defender_core.domain.*;
import com.ticket.defender_core.security.AdminRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
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
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "ticket-defender.evidence-preservation.enabled=true"
})
@EnabledIfEnvironmentVariable(named = "TD_ISSUE32_MYSQL_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:13307/td_issue32.*")
class CaseW07ConcurrentMySqlTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID EVENT = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final String PROFILE = "W07_MYSQL_LOCKED";
    private static final long MAX_BYTES = 20L * 1024 * 1024;

    @Autowired private CaseService caseService;
    @Autowired private CasePreparationService preparation;
    @Autowired private EvidenceIntegrityService integrity;
    @Autowired private EvidencePreservationService preservation;
    @Autowired private CaseRepository cases;
    @Autowired private CaseSourceReferenceRepository sources;
    @Autowired private CaseActivityRepository activities;
    @Autowired private CaseIdempotencyRepository idempotency;
    @Autowired private EvidencePreservationPolicyRepository policies;
    @Autowired private EvidencePreservationRecordRepository preservationRecords;
    @Autowired private EvidencePreservationAttemptRepository attempts;
    @Autowired private FanReportRepository reports;
    @Autowired private EvidenceAssetRepository assets;
    @Autowired private AdminMembershipRepository memberships;
    @Autowired private MembershipRoleRepository roles;
    @Autowired private MembershipEventGrantRepository grants;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private EvidenceObjectStorage objectStorage;
    @MockitoBean private EvidencePreservationStorage preservationStorage;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TD_ISSUE32_MYSQL_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("TD_ISSUE32_MYSQL_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("TD_ISSUE32_MYSQL_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @BeforeEach
    void setupStorageProfile() {
        reset(objectStorage, preservationStorage);
        when(preservationStorage.profile()).thenReturn(PROFILE);
    }

    @Test
    void sameKeyConcurrentCommandCommitsOneRecordActivityAndIdempotencyResult() throws Exception {
        Fixture fixture = fixture();
        String key = UUID.randomUUID().toString();
        EvidencePreservationService.Command command = command(fixture);

        List<String> outcomes = race(fixture.subject(),
                () -> preservation.preserve(fixture.caseId(), command, key).recordId().toString());

        assertThat(outcomes).allMatch(result -> result.startsWith("OK:"));
        assertThat(outcomes.stream().distinct()).hasSize(1);
        assertThat(preservationRecords.findByCaseIdOrderByVerifiedAtAscIdAsc(fixture.caseId())).hasSize(1);
        assertThat(preservationActivityCount(fixture.caseId())).isEqualTo(1);
        assertThat(attempts.findByCaseIdOrderByAttemptedAtAscIdAsc(fixture.caseId()).stream()
                .filter(attempt -> attempt.getOutcome() == EvidencePreservationAttempt.Outcome.VERIFIED)).hasSize(1);
        assertThat(idempotency.findByTenantIdAndActorMembershipIdAndOperationAndTargetCaseIdAndKeyDigest(
                TENANT, fixture.memberId(), "EVIDENCE_PRESERVE", fixture.caseId(),
                CaseSnapshotManifest.sha256(key))).isPresent();
    }

    @Test
    void distinctKeysAtSameCaseVersionCannotBothCommit() throws Exception {
        Fixture fixture = fixture();
        EvidencePreservationService.Command command = command(fixture);

        List<String> outcomes = race(fixture.subject(), () -> preservation.preserve(
                fixture.caseId(), command, UUID.randomUUID().toString()).recordId().toString());

        assertThat(outcomes.stream().filter(result -> result.startsWith("OK:"))).hasSize(1);
        assertThat(outcomes.stream().filter(result -> !result.startsWith("OK:"))).hasSize(1);
        assertThat(preservationRecords.findByCaseIdOrderByVerifiedAtAscIdAsc(fixture.caseId())).hasSize(1);
        assertThat(preservationActivityCount(fixture.caseId())).isEqualTo(1);
    }

    @Test
    void failedSuccessActivityInsertRollsBackRecordCaseVersionAndIdempotency() {
        Fixture fixture = fixture();
        long before = cases.findById(fixture.caseId()).orElseThrow().getCaseVersion();
        String trigger = "td_w07_activity_failure";
        jdbc.execute("DROP TRIGGER IF EXISTS " + trigger);
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON case_activities FOR EACH ROW "
                + "BEGIN IF NEW.case_id = '" + fixture.caseId() + "' "
                + "AND NEW.event_type = 'EVIDENCE_PRESERVATION_VERIFIED' THEN "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'synthetic W07 activity failure'; "
                + "END IF; END");
        String key = UUID.randomUUID().toString();
        try {
            assertThatThrownBy(() -> preservation.preserve(fixture.caseId(), command(fixture), key))
                    .isInstanceOfSatisfying(CaseApiException.class, error -> {
                        assertThat(error.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                        assertThat(error.code()).isEqualTo("DEPENDENCY_UNAVAILABLE");
                    });
            assertThat(preservationRecords.findByCaseIdOrderByVerifiedAtAscIdAsc(fixture.caseId())).isEmpty();
            assertThat(preservationActivityCount(fixture.caseId())).isZero();
            assertThat(cases.findById(fixture.caseId()).orElseThrow().getCaseVersion()).isEqualTo(before);
            assertThat(idempotency.findByTenantIdAndActorMembershipIdAndOperationAndTargetCaseIdAndKeyDigest(
                    TENANT, fixture.memberId(), "EVIDENCE_PRESERVE", fixture.caseId(),
                    CaseSnapshotManifest.sha256(key))).isEmpty();
            assertThat(attempts.findByCaseIdOrderByAttemptedAtAscIdAsc(fixture.caseId()))
                    .singleElement().extracting(EvidencePreservationAttempt::getOutcome)
                    .isEqualTo(EvidencePreservationAttempt.Outcome.CONFLICT);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS " + trigger);
        }
    }

    @Test
    void failedFailureAuditInsertPreservesOriginalObjectLockError() {
        Fixture fixture = fixture();
        long before = cases.findById(fixture.caseId()).orElseThrow().getCaseVersion();
        String trigger = "td_w07_failure_audit_failure";
        jdbc.execute("DROP TRIGGER IF EXISTS " + trigger);
        jdbc.execute("CREATE TRIGGER " + trigger
                + " BEFORE INSERT ON evidence_preservation_attempts FOR EACH ROW "
                + "BEGIN IF NEW.case_id = '" + fixture.caseId() + "' "
                + "AND NEW.reason_code = 'OBJECT_LOCK_NOT_VERIFIED' THEN "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'synthetic W07 failure audit failure'; "
                + "END IF; END");
        String key = UUID.randomUUID().toString();
        when(preservationStorage.verifyLocked(anyString(), isNull(), eq(fixture.sha256()), eq(4L),
                eq(MAX_BYTES))).thenThrow(new EvidencePreservationStorage.ObjectLockNotVerifiedException());
        try {
            Throwable failure = catchThrowable(() -> preservation.preserve(
                    fixture.caseId(), command(fixture), key));
            assertThat(failure).isInstanceOfSatisfying(CaseApiException.class, error -> {
                assertThat(error.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                assertThat(error.code()).isEqualTo("OBJECT_LOCK_NOT_VERIFIED");
                assertThat(error.getSuppressed()).singleElement()
                        .isInstanceOf(DataAccessException.class);
            });
            assertThat(preservationRecords.findByCaseIdOrderByVerifiedAtAscIdAsc(fixture.caseId())).isEmpty();
            assertThat(preservationActivityCount(fixture.caseId())).isZero();
            assertThat(cases.findById(fixture.caseId()).orElseThrow().getCaseVersion()).isEqualTo(before);
            assertThat(idempotency.findByTenantIdAndActorMembershipIdAndOperationAndTargetCaseIdAndKeyDigest(
                    TENANT, fixture.memberId(), "EVIDENCE_PRESERVE", fixture.caseId(),
                    CaseSnapshotManifest.sha256(key))).isEmpty();
            assertThat(attempts.findByCaseIdOrderByAttemptedAtAscIdAsc(fixture.caseId())).isEmpty();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS " + trigger);
        }
    }

    @Test
    void policyRevocationDuringObjectReadPreventsSuccessCommit() {
        Fixture fixture = fixture();
        when(preservationStorage.verifyLocked(anyString(), isNull(), eq(fixture.sha256()), eq(4L),
                eq(MAX_BYTES))).thenAnswer(invocation -> {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            policies.saveAndFlush(new EvidencePreservationPolicy(fixture.policyId(), 2, TENANT, EVENT,
                    EvidencePreservationPolicy.Status.REVOKED, now.minusDays(1), now.plusDays(10),
                    10, PROFILE, EvidencePreservationPolicy.Mode.GOVERNANCE,
                    "synthetic-test-authority", now.minusHours(2), now.minusHours(1), now));
            return new EvidencePreservationStorage.LockedObject("locked-version", fixture.sha256(), 4,
                    EvidencePreservationPolicy.Mode.GOVERNANCE, now.plusDays(5));
        });

        assertThatThrownBy(() -> preservation.preserve(fixture.caseId(), command(fixture),
                UUID.randomUUID().toString())).isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("PRESERVATION_POLICY_NOT_APPROVED"));
        assertThat(preservationRecords.findByCaseIdOrderByVerifiedAtAscIdAsc(fixture.caseId())).isEmpty();
        assertThat(preservationActivityCount(fixture.caseId())).isZero();
    }

    private Fixture fixture() {
        UUID memberId = UUID.randomUUID();
        String subject = "w07-mysql-reviewer-" + memberId;
        memberships.saveAndFlush(new AdminMembershipEntity(memberId, TENANT, subject, AccessRecordStatus.ACTIVE));
        roles.saveAndFlush(new MembershipRole(UUID.randomUUID(), memberId, AdminRole.REVIEWER));
        grants.saveAndFlush(new MembershipEventGrant(UUID.randomUUID(), memberId, TENANT, EVENT));
        UUID reportId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        FanReport report = new FanReport(reportId, "FR-" + reportId, TENANT, EVENT,
                now.plusDays(1), null, FanReport.ReportType.OTHER, "Synthetic W07 MySQL report",
                null, null, null, null, now, UUID.randomUUID().toString(),
                "d".repeat(64), "e".repeat(64));
        report.markReceived();
        reports.saveAndFlush(report);
        UUID evidenceId = UUID.randomUUID();
        String objectKey = "fan-reports/" + reportId + "/" + evidenceId;
        String sha256 = "a".repeat(64);
        EvidenceAsset asset = new EvidenceAsset(evidenceId, reportId, objectKey, "image/png", 4,
                sha256, now, report.getRetentionUntil());
        asset.markStored("etag");
        assets.saveAndFlush(asset);
        when(objectStorage.head(objectKey)).thenReturn(
                new EvidenceObjectStorage.ObjectMetadata(4, "image/png", "etag"));
        when(objectStorage.verifyBytes(objectKey, "etag", MAX_BYTES)).thenReturn(
                new EvidenceObjectStorage.ByteVerification(4, "image/png", sha256, "etag",
                        EvidenceObjectStorage.MarkerKind.ETAG));
        authenticate(subject);
        CaseDto.View opened = caseService.create(new CaseDto.Create(EVENT, Case.Type.FAN_REPORT,
                List.of(), List.of(reportId), List.of(evidenceId), "Investigate synthetic report", null, null),
                UUID.randomUUID().toString());
        CaseDto.View assigned = caseService.assign(opened.caseId(),
                new CaseDto.Assign(memberId, opened.caseVersion(), "Assign reviewer"),
                UUID.randomUUID().toString());
        CaseDto.View triaged = caseService.transition(opened.caseId(),
                new CaseDto.Transition(Case.Status.TRIAGED, assigned.caseVersion(), "Triage case",
                        "Synthetic classification", null, null, null, null, null),
                UUID.randomUUID().toString());
        CaseDto.View reviewing = caseService.transition(opened.caseId(),
                new CaseDto.Transition(Case.Status.IN_REVIEW, triaged.caseVersion(), "Begin review",
                        null, null, null, null, null, null), UUID.randomUUID().toString());
        var snapshot = preparation.capture(opened.caseId(),
                new CasePreparationDto.Capture(reviewing.caseVersion(), "Capture synthetic evidence"),
                UUID.randomUUID().toString());
        UUID sourceId = sources.findByCaseIdOrderByLinkedAtAscIdAsc(opened.caseId()).stream()
                .filter(source -> source.getSourceType() == CaseSourceReference.Type.EVIDENCE_ASSET)
                .findFirst().orElseThrow().getId();
        EvidenceIntegrityDto.Verification verification = integrity.verify(opened.caseId(),
                new EvidenceIntegrityDto.VerifyInput(
                        cases.findById(opened.caseId()).orElseThrow().getCaseVersion(),
                        snapshot.evidenceSnapshotId(), sourceId, evidenceId, "Verify synthetic bytes"),
                UUID.randomUUID().toString());
        UUID policyId = UUID.randomUUID();
        policies.saveAndFlush(new EvidencePreservationPolicy(policyId, 1, TENANT, EVENT,
                EvidencePreservationPolicy.Status.APPROVED, now.minusDays(1), now.plusDays(30),
                10, PROFILE, EvidencePreservationPolicy.Mode.GOVERNANCE,
                "synthetic-test-authority", now.minusHours(2), now.minusHours(1), null));
        when(preservationStorage.verifyLocked(anyString(), isNull(), eq(sha256), eq(4L), eq(MAX_BYTES)))
                .thenReturn(new EvidencePreservationStorage.LockedObject("locked-" + evidenceId,
                        sha256, 4, EvidencePreservationPolicy.Mode.GOVERNANCE, now.plusDays(5)));
        return new Fixture(opened.caseId(), snapshot.evidenceSnapshotId(), sourceId, evidenceId,
                verification.recordId(), policyId, subject, memberId, sha256);
    }

    private EvidencePreservationService.Command command(Fixture fixture) {
        return new EvidencePreservationService.Command(
                cases.findById(fixture.caseId()).orElseThrow().getCaseVersion(), fixture.snapshotId(),
                fixture.sourceId(), fixture.evidenceId(), fixture.verificationId(), fixture.policyId(),
                "Verify synthetic preservation");
    }

    private long preservationActivityCount(UUID caseId) {
        return activities.findByCaseId(caseId, org.springframework.data.domain.Pageable.unpaged()).stream()
                .filter(activity -> activity.getEventType().equals("EVIDENCE_PRESERVATION_VERIFIED"))
                .count();
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
                } catch (CaseApiException error) {
                    return error.code();
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
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(
                Jwt.withTokenValue("synthetic").header("alg", "RS256").subject(subject).build());
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private record Fixture(UUID caseId, UUID snapshotId, UUID sourceId, UUID evidenceId,
                           UUID verificationId, UUID policyId, String subject, UUID memberId,
                           String sha256) { }
    private interface Action { String execute(); }
}
