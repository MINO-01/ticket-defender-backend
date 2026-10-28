package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.in.web.dto.ApprovalRequestDto;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.reset;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "ticket-defender.evidence-preservation.enabled=true",
        "ticket-defender.evidence-preservation.max-bytes=20971520"
})
@EnabledIfEnvironmentVariable(named = "TD_ISSUE32_MYSQL_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:13307/td_issue32.*")
class CaseW08aConcurrentMySqlTest {
    private static final String PROFILE = "W08_MYSQL_LOCKED";
    private static final long MAX_BYTES = 20L * 1024 * 1024;

    @Autowired private CaseService caseService;
    @Autowired private CasePreparationService preparation;
    @Autowired private EvidenceIntegrityService integrity;
    @Autowired private EvidencePreservationService preservation;
    @Autowired private ApprovalRequestService approvals;
    @Autowired private CaseRepository cases;
    @Autowired private CaseSourceReferenceRepository sources;
    @Autowired private ReportVersionRepository reports;
    @Autowired private ApprovalRequestRepository requests;
    @Autowired private ApprovalAuditEventRepository audits;
    @Autowired private CaseActivityRepository activities;
    @Autowired private CaseIdempotencyRepository idempotency;
    @Autowired private EvidencePreservationPolicyRepository policies;
    @Autowired private TenantRepository tenants;
    @Autowired private TenantEventRepository events;
    @Autowired private AdminMembershipRepository memberships;
    @Autowired private MembershipRoleRepository roles;
    @Autowired private MembershipEventGrantRepository grants;
    @Autowired private FanReportRepository fanReports;
    @Autowired private EvidenceAssetRepository assets;
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
    void setupStorage() {
        reset(objectStorage, preservationStorage);
        given(preservationStorage.profile()).willReturn(PROFILE);
    }

    @Test
    void sameKeyConcurrentSubmissionCommitsOneRequestAuditActivityAndIdempotencyResult() throws Exception {
        Fixture fixture = fixture();
        String key = UUID.randomUUID().toString();
        ApprovalRequestDto.Submit command = command(fixture);

        List<String> outcomes = race(fixture.subject(),
                () -> approvals.submit(fixture.caseId(), command, key).approvalRequestId().toString(),
                () -> approvals.submit(fixture.caseId(), command, key).approvalRequestId().toString());

        assertThat(outcomes).allMatch(outcome -> outcome.startsWith("OK:"));
        assertThat(outcomes.stream().distinct()).hasSize(1);
        ApprovalRequest request = requests.findByTenantIdAndCaseIdAndStatus(
                fixture.tenantId(), fixture.caseId(), ApprovalRequest.Status.PENDING).orElseThrow();
        assertThat(audits.findByApprovalRequestIdOrderByOccurredAtAscIdAsc(request.getId())).hasSize(1);
        assertThat(activityCount(fixture.caseId())).isEqualTo(1);
        assertThat(idempotency.findByTenantIdAndActorMembershipIdAndOperationAndTargetCaseIdAndKeyDigest(
                fixture.tenantId(), fixture.memberId(), "APPROVAL_REQUEST_CREATE", fixture.caseId(),
                CaseSnapshotManifest.sha256(key))).isPresent();
    }

    @Test
    void distinctKeysAtSameCaseVersionCannotBothCreatePendingRequests() throws Exception {
        Fixture fixture = fixture();
        ApprovalRequestDto.Submit command = command(fixture);

        List<String> outcomes = race(fixture.subject(),
                () -> approvals.submit(fixture.caseId(), command, UUID.randomUUID().toString())
                        .approvalRequestId().toString(),
                () -> approvals.submit(fixture.caseId(), command, UUID.randomUUID().toString())
                        .approvalRequestId().toString());

        assertThat(outcomes.stream().filter(outcome -> outcome.startsWith("OK:"))).hasSize(1);
        assertThat(outcomes.stream().filter(outcome -> !outcome.startsWith("OK:"))).hasSize(1);
        assertThat(requests.findByTenantIdAndCaseIdAndStatus(
                fixture.tenantId(), fixture.caseId(), ApprovalRequest.Status.PENDING)).isPresent();
        assertThat(activityCount(fixture.caseId())).isEqualTo(1);
    }

    @Test
    void reportRevisionAndApprovalSubmissionRaceHasOneAtomicWinner() throws Exception {
        Fixture fixture = fixture();
        long caseVersion = cases.findById(fixture.caseId()).orElseThrow().getCaseVersion();
        ApprovalRequestDto.Submit approval = command(fixture);
        CasePreparationDto.DraftInput revision = new CasePreparationDto.DraftInput(
                caseVersion, fixture.snapshotId(), "Concurrent revised report",
                "Concurrent revised assessment for synthetic approval",
                "Concurrent synthetic limitations", "Concurrent synthetic conclusion",
                "Race report revision against approval request");

        List<String> outcomes = race(fixture.subject(),
                () -> approvals.submit(fixture.caseId(), approval, UUID.randomUUID().toString())
                        .approvalRequestId().toString(),
                () -> preparation.draft(fixture.caseId(), revision, UUID.randomUUID().toString())
                        .reportVersionId().toString());

        assertThat(outcomes.stream().filter(outcome -> outcome.startsWith("OK:"))).hasSize(1);
        assertThat(outcomes.stream().filter(outcome -> !outcome.startsWith("OK:"))).hasSize(1);
        Case target = cases.findById(fixture.caseId()).orElseThrow();
        boolean approvalWon = requests.findByTenantIdAndCaseIdAndStatus(
                fixture.tenantId(), fixture.caseId(), ApprovalRequest.Status.PENDING).isPresent();
        if (approvalWon) {
            assertThat(target.getStatus()).isEqualTo(Case.Status.READY_FOR_APPROVAL);
            assertThat(reports.findByCaseIdOrderByRevisionDesc(fixture.caseId())).hasSize(1);
        } else {
            assertThat(target.getStatus()).isEqualTo(Case.Status.IN_REVIEW);
            assertThat(reports.findByCaseIdOrderByRevisionDesc(fixture.caseId())).hasSize(2);
        }
    }

    @Test
    void failedApprovalAuditInsertRollsBackCaseReportRequestActivityAndIdempotency() {
        Fixture fixture = fixture();
        long caseVersion = cases.findById(fixture.caseId()).orElseThrow().getCaseVersion();
        String key = UUID.randomUUID().toString();
        String trigger = "td_w08_approval_audit_failure";
        jdbc.execute("DROP TRIGGER IF EXISTS " + trigger);
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON approval_audit_events FOR EACH ROW "
                + "BEGIN IF NEW.case_id = '" + fixture.caseId() + "' THEN "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'synthetic W08 audit failure'; "
                + "END IF; END");
        try {
            assertThatThrownBy(() -> approvals.submit(fixture.caseId(), command(fixture), key))
                    .isInstanceOf(DataAccessException.class);
            Case target = cases.findById(fixture.caseId()).orElseThrow();
            ReportVersion report = reports.findById(fixture.reportVersionId()).orElseThrow();
            assertThat(target.getStatus()).isEqualTo(Case.Status.IN_REVIEW);
            assertThat(target.getCurrentReportVersionId()).isNull();
            assertThat(target.getCaseVersion()).isEqualTo(caseVersion);
            assertThat(report.getStatus()).isEqualTo(ReportVersion.Status.DRAFT.name());
            assertThat(report.getVersion()).isZero();
            assertThat(requests.findByReportVersionId(fixture.reportVersionId())).isEmpty();
            assertThat(activityCount(fixture.caseId())).isZero();
            assertThat(idempotency.findByTenantIdAndActorMembershipIdAndOperationAndTargetCaseIdAndKeyDigest(
                    fixture.tenantId(), fixture.memberId(), "APPROVAL_REQUEST_CREATE", fixture.caseId(),
                    CaseSnapshotManifest.sha256(key))).isEmpty();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS " + trigger);
        }
    }

    private Fixture fixture() {
        UUID tenant = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        String subject = "w08-mysql-reviewer-" + member;
        tenants.saveAndFlush(new Tenant(tenant, "Synthetic W08 MySQL tenant", AccessRecordStatus.ACTIVE));
        events.saveAndFlush(new TenantEvent(event, tenant, "w08-mysql-" + event,
                "Synthetic W08 MySQL event", AccessRecordStatus.ACTIVE));
        memberships.saveAndFlush(new AdminMembershipEntity(member, tenant, subject, AccessRecordStatus.ACTIVE));
        roles.saveAndFlush(new MembershipRole(UUID.randomUUID(), member, AdminRole.REVIEWER));
        grants.saveAndFlush(new MembershipEventGrant(UUID.randomUUID(), member, tenant, event));

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        UUID fanReportId = UUID.randomUUID();
        FanReport fanReport = new FanReport(fanReportId, "FR-" + fanReportId, tenant, event,
                now.plusDays(30), null, FanReport.ReportType.OTHER, "Synthetic W08 MySQL evidence",
                null, null, null, null, now, UUID.randomUUID().toString(),
                "d".repeat(64), "e".repeat(64));
        fanReport.markReceived();
        fanReports.saveAndFlush(fanReport);
        UUID evidenceId = UUID.randomUUID();
        String objectKey = "fan-reports/" + fanReportId + "/" + evidenceId;
        String sha256 = "a".repeat(64);
        EvidenceAsset asset = new EvidenceAsset(evidenceId, fanReportId, objectKey,
                "image/png", 4, sha256, now, fanReport.getRetentionUntil());
        asset.markStored("etag-" + evidenceId);
        assets.saveAndFlush(asset);
        given(objectStorage.head(objectKey)).willReturn(
                new EvidenceObjectStorage.ObjectMetadata(4, "image/png", asset.getObjectVersion()));
        given(objectStorage.verifyBytes(objectKey, asset.getObjectVersion(), MAX_BYTES)).willReturn(
                new EvidenceObjectStorage.ByteVerification(4, "image/png", sha256,
                        asset.getObjectVersion(), EvidenceObjectStorage.MarkerKind.ETAG));
        authenticate(subject);
        CaseDto.View opened = caseService.create(new CaseDto.Create(event, Case.Type.FAN_REPORT,
                List.of(), List.of(fanReportId), List.of(evidenceId),
                "Investigate concurrent approval evidence", null, null), UUID.randomUUID().toString());
        CaseDto.View assigned = caseService.assign(opened.caseId(),
                new CaseDto.Assign(member, opened.caseVersion(), "Assign MySQL reviewer"),
                UUID.randomUUID().toString());
        CaseDto.View triaged = caseService.transition(opened.caseId(), new CaseDto.Transition(
                Case.Status.TRIAGED, assigned.caseVersion(), "Triage MySQL Case",
                "Synthetic W08 classification", null, null, null, null, null),
                UUID.randomUUID().toString());
        CaseDto.View reviewing = caseService.transition(opened.caseId(), new CaseDto.Transition(
                Case.Status.IN_REVIEW, triaged.caseVersion(), "Begin MySQL review",
                null, null, null, null, null, null), UUID.randomUUID().toString());
        CasePreparationDto.Snapshot snapshot = preparation.capture(opened.caseId(),
                new CasePreparationDto.Capture(reviewing.caseVersion(), "Capture MySQL evidence"),
                UUID.randomUUID().toString());
        CasePreparationDto.Draft draft = preparation.draft(opened.caseId(),
                new CasePreparationDto.DraftInput(cases.findById(opened.caseId()).orElseThrow().getCaseVersion(),
                        snapshot.evidenceSnapshotId(), "Synthetic MySQL approval report",
                        "Synthetic MySQL assessment for independent approval",
                        "Synthetic MySQL limitations", "Synthetic MySQL conclusion",
                        "Seal MySQL approval report"), UUID.randomUUID().toString());
        UUID sourceId = sources.findByCaseIdOrderByLinkedAtAscIdAsc(opened.caseId()).stream()
                .filter(source -> source.getSourceType() == CaseSourceReference.Type.EVIDENCE_ASSET)
                .findFirst().orElseThrow().getId();
        EvidenceIntegrityDto.Verification verification = integrity.verify(opened.caseId(),
                new EvidenceIntegrityDto.VerifyInput(cases.findById(opened.caseId()).orElseThrow().getCaseVersion(),
                        snapshot.evidenceSnapshotId(), sourceId, evidenceId,
                        "Verify MySQL approval bytes"), UUID.randomUUID().toString());
        UUID policyId = UUID.randomUUID();
        policies.saveAndFlush(new EvidencePreservationPolicy(policyId, 1, tenant, event,
                EvidencePreservationPolicy.Status.APPROVED, now.minusDays(1), now.plusDays(20),
                10, PROFILE, EvidencePreservationPolicy.Mode.GOVERNANCE,
                "synthetic-mysql-authority", now.minusHours(2), now.minusHours(1), null));
        given(preservationStorage.verifyLocked(anyString(), isNull(), eq(sha256), eq(4L), eq(MAX_BYTES)))
                .willReturn(new EvidencePreservationStorage.LockedObject("locked-" + evidenceId,
                        sha256, 4, EvidencePreservationPolicy.Mode.GOVERNANCE, now.plusDays(5)));
        preservation.preserve(opened.caseId(), new EvidencePreservationService.Command(
                cases.findById(opened.caseId()).orElseThrow().getCaseVersion(),
                snapshot.evidenceSnapshotId(), sourceId, evidenceId, verification.recordId(), policyId,
                "Verify MySQL immutable evidence"), UUID.randomUUID().toString());
        return new Fixture(tenant, event, opened.caseId(), snapshot.evidenceSnapshotId(),
                draft.reportVersionId(), draft.contentSha256(), member, subject);
    }

    private ApprovalRequestDto.Submit command(Fixture fixture) {
        return new ApprovalRequestDto.Submit(fixture.reportVersionId(), fixture.snapshotId(),
                cases.findById(fixture.caseId()).orElseThrow().getCaseVersion(),
                reports.findById(fixture.reportVersionId()).orElseThrow().getVersion(),
                fixture.contentSha256(), "Request concurrent independent approval");
    }

    private long activityCount(UUID caseId) {
        return activities.findByCaseId(caseId, org.springframework.data.domain.Pageable.unpaged()).stream()
                .filter(activity -> "APPROVAL_REQUESTED".equals(activity.getEventType())).count();
    }

    private static List<String> race(String subject, Action first, Action second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean run = new AtomicBoolean();
        List<Future<String>> futures = new ArrayList<>();
        var workers = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon(true).factory());
        try {
            for (Action action : List.of(first, second)) futures.add(workers.submit(() -> {
                try {
                    authenticate(subject);
                    ready.countDown();
                    start.await();
                    if (!run.get()) return "ABORTED";
                    return "OK:" + action.execute();
                } catch (CaseApiException error) {
                    return error.code();
                } catch (RuntimeException error) {
                    return error.getClass().getSimpleName();
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

    private record Fixture(UUID tenantId, UUID eventId, UUID caseId, UUID snapshotId,
                           UUID reportVersionId, String contentSha256, UUID memberId,
                           String subject) { }
    private interface Action { String execute(); }
}
