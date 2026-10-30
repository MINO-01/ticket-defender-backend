package com.ticket.defender_core.integration;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import com.ticket.defender_core.adapter.in.web.dto.ApprovalRequestDto;
import com.ticket.defender_core.adapter.in.web.dto.ApprovalReviewDto;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "ticket-defender.evidence-preservation.enabled=true",
        "ticket-defender.evidence-preservation.max-bytes=20971520"
})
@EnabledIfEnvironmentVariable(named = "TD_ISSUE32_MYSQL_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:13307/td_issue32.*")
class CaseW08bConcurrentMySqlTest {
    private static final String PROFILE = "W08B_MYSQL_LOCKED";
    private static final long MAX_BYTES = 20L * 1024 * 1024;

    @Autowired private CaseService caseService;
    @Autowired private CasePreparationService preparation;
    @Autowired private EvidenceIntegrityService integrity;
    @Autowired private EvidencePreservationService preservation;
    @Autowired private ApprovalRequestService approvals;
    @Autowired private ApprovalReviewService review;
    @Autowired private ApprovalReviewWriteService reviewWriter;
    @MockitoSpyBean private CaseRepository cases;
    @Autowired private CaseSourceReferenceRepository sources;
    @Autowired private ReportVersionRepository reports;
    @MockitoSpyBean private ApprovalRequestRepository requests;
    @Autowired private ReviewReceiptRepository receipts;
    @Autowired private ApprovalDecisionRepository decisions;
    @Autowired private ApprovalAuditEventRepository audits;
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
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;
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
        reset(objectStorage, preservationStorage, cases, requests);
        given(preservationStorage.profile()).willReturn(PROFILE);
    }

    @Test
    void oppositeDecisionsWithSeparateReceiptsCommitOneImmutableDecision() throws Exception {
        Fixture fixture = fixture();
        ApprovalReviewDto.Receipt approveReceipt = receipt(fixture.approverOneSubject(), fixture.requestId());
        ApprovalReviewDto.Receipt rejectReceipt = receipt(fixture.approverTwoSubject(), fixture.requestId());
        ApprovalReviewDto.DecisionInput approve = decision(fixture, approveReceipt.reviewReceiptToken(),
                ApprovalDecision.Outcome.APPROVE, "Approve the independently reviewed synthetic package");
        ApprovalReviewDto.DecisionInput reject = decision(fixture, rejectReceipt.reviewReceiptToken(),
                ApprovalDecision.Outcome.REJECT, "Reject the independently reviewed synthetic package");

        List<String> outcomes = race(
                new Attempt(fixture.approverOneSubject(), () -> review.decide(fixture.requestId(), approve,
                        UUID.randomUUID().toString()).outcome().name()),
                new Attempt(fixture.approverTwoSubject(), () -> review.decide(fixture.requestId(), reject,
                        UUID.randomUUID().toString()).outcome().name()));

        assertThat(outcomes.stream().filter(value -> value.startsWith("OK:"))).hasSize(1);
        assertThat(outcomes.stream().filter(value -> !value.startsWith("OK:")))
                .containsExactly("APPROVAL_REQUEST_CLOSED");
        ApprovalDecision decision = decisions.findByApprovalRequestId(fixture.requestId()).orElseThrow();
        ApprovalRequest request = requests.findById(fixture.requestId()).orElseThrow();
        assertDecisionTerminalState(fixture, request, decision);
        assertAuditCount(fixture.requestId(), "APPROVAL_DECIDED", 1);
        assertThat(receipts.findAll().stream().filter(receipt -> fixture.requestId()
                .equals(receipt.getApprovalRequestId()))
                .filter(receipt -> receipt.getStatus() == ReviewReceipt.Status.CONSUMED)).hasSize(1);
        assertThat(receipts.findAll().stream().filter(receipt -> fixture.requestId()
                .equals(receipt.getApprovalRequestId()))
                .filter(receipt -> receipt.getStatus() == ReviewReceipt.Status.ISSUED)).isEmpty();
    }

    @Test
    void sameReceiptWithDistinctKeysIsConsumedOnlyOnce() throws Exception {
        Fixture fixture = fixture();
        ApprovalReviewDto.Receipt receipt = receipt(fixture.approverOneSubject(), fixture.requestId());
        ApprovalReviewDto.DecisionInput approve = decision(fixture, receipt.reviewReceiptToken(),
                ApprovalDecision.Outcome.APPROVE, "Approve once using the same review receipt");
        ApprovalReviewDto.DecisionInput reject = decision(fixture, receipt.reviewReceiptToken(),
                ApprovalDecision.Outcome.REJECT, "Attempt a second receipt consumption");

        List<String> outcomes = race(
                new Attempt(fixture.approverOneSubject(), () -> review.decide(fixture.requestId(), approve,
                        UUID.randomUUID().toString()).outcome().name()),
                new Attempt(fixture.approverOneSubject(), () -> review.decide(fixture.requestId(), reject,
                        UUID.randomUUID().toString()).outcome().name()));

        assertThat(outcomes.stream().filter(value -> value.startsWith("OK:"))).hasSize(1);
        assertThat(outcomes.stream().filter(value -> !value.startsWith("OK:")))
                .containsExactly("APPROVAL_REQUEST_CLOSED");
        ApprovalDecision decision = decisions.findByApprovalRequestId(fixture.requestId()).orElseThrow();
        assertDecisionTerminalState(fixture, requests.findById(fixture.requestId()).orElseThrow(), decision);
        assertAuditCount(fixture.requestId(), "APPROVAL_DECIDED", 1);
        assertThat(receipts.findById(receipt.reviewReceiptId()).orElseThrow().getStatus())
                .isEqualTo(ReviewReceipt.Status.CONSUMED);
    }

    @Test
    void decisionAndWithdrawalRaceLeaveOneTerminalPathAndReleaseActiveSlot() throws Exception {
        Fixture fixture = fixture();
        ApprovalReviewDto.Receipt receipt = receipt(fixture.approverOneSubject(), fixture.requestId());
        ApprovalReviewDto.DecisionInput decision = decision(fixture, receipt.reviewReceiptToken(),
                ApprovalDecision.Outcome.APPROVE, "Approve unless the reviewer withdraws first");
        ApprovalReviewDto.WithdrawInput withdrawal = withdraw(fixture,
                "Withdraw the synthetic request before a decision is committed");

        List<String> outcomes = race(
                new Attempt(fixture.approverOneSubject(), () -> review.decide(fixture.requestId(), decision,
                        UUID.randomUUID().toString()).outcome().name()),
                new Attempt(fixture.reviewerSubject(), () -> review.withdraw(fixture.requestId(), withdrawal,
                        UUID.randomUUID().toString()).requestStatus().name()));

        assertThat(outcomes.stream().filter(value -> value.startsWith("OK:"))).hasSize(1);
        assertThat(outcomes.stream().filter(value -> !value.startsWith("OK:")))
                .containsExactly("APPROVAL_REQUEST_CLOSED");
        ApprovalRequest request = requests.findById(fixture.requestId()).orElseThrow();
        assertThat(request.getActiveCaseId()).isNull();
        if (request.getStatus() == ApprovalRequest.Status.APPROVED) {
            assertDecisionTerminalState(fixture, request,
                    decisions.findByApprovalRequestId(fixture.requestId()).orElseThrow());
            assertAuditCount(fixture.requestId(), "APPROVAL_DECIDED", 1);
            assertAuditCount(fixture.requestId(), "APPROVAL_WITHDRAWN", 0);
            assertThat(receipts.findById(receipt.reviewReceiptId()).orElseThrow().getStatus())
                    .isEqualTo(ReviewReceipt.Status.CONSUMED);
        } else {
            assertThat(request.getStatus()).isEqualTo(ApprovalRequest.Status.WITHDRAWN);
            assertThat(decisions.findByApprovalRequestId(fixture.requestId())).isEmpty();
            assertDecisionCount(fixture.requestId(), 0);
            assertThat(cases.findById(fixture.caseId()).orElseThrow().getStatus())
                    .isEqualTo(Case.Status.IN_REVIEW);
            assertThat(reports.findById(fixture.reportVersionId()).orElseThrow().getStatus())
                    .isEqualTo(ReportVersion.Status.WITHDRAWN.name());
            assertAuditCount(fixture.requestId(), "APPROVAL_DECIDED", 0);
            assertAuditCount(fixture.requestId(), "APPROVAL_WITHDRAWN", 1);
            assertThat(receipts.findById(receipt.reviewReceiptId()).orElseThrow().getStatus())
                    .isEqualTo(ReviewReceipt.Status.REVOKED);
        }
    }

    @Test
    void decisionAndTrustedInvalidationRaceLeaveOneTerminalPath() throws Exception {
        Fixture fixture = fixture();
        ApprovalReviewDto.Receipt receipt = receipt(fixture.approverOneSubject(), fixture.requestId());
        ApprovalReviewDto.DecisionInput decision = decision(fixture, receipt.reviewReceiptToken(),
                ApprovalDecision.Outcome.APPROVE, "Approve unless the trusted system invalidates first");
        UUID invalidator = memberships.findBySubjectAndTenantId(fixture.reviewerSubject(), fixture.tenantId())
                .orElseThrow().getId();

        List<String> outcomes = race(
                new Attempt(fixture.approverOneSubject(), () -> review.decide(fixture.requestId(), decision,
                        UUID.randomUUID().toString()).outcome().name()),
                new Attempt(fixture.reviewerSubject(), () -> reviewWriter.invalidateFromTrustedSystem(
                        fixture.requestId(), invalidator,
                        "Synthetic evidence change invalidated the pending request").getStatus().name()));

        assertThat(outcomes.stream().filter(value -> value.startsWith("OK:"))).hasSize(1);
        assertThat(outcomes.stream().filter(value -> !value.startsWith("OK:")))
                .containsExactly("APPROVAL_REQUEST_CLOSED");
        ApprovalRequest request = requests.findById(fixture.requestId()).orElseThrow();
        assertThat(request.getActiveCaseId()).isNull();
        if (request.getStatus() == ApprovalRequest.Status.APPROVED) {
            assertDecisionTerminalState(fixture, request,
                    decisions.findByApprovalRequestId(fixture.requestId()).orElseThrow());
            assertAuditCount(fixture.requestId(), "APPROVAL_DECIDED", 1);
            assertAuditCount(fixture.requestId(), "APPROVAL_INVALIDATED", 0);
            assertThat(receipts.findById(receipt.reviewReceiptId()).orElseThrow().getStatus())
                    .isEqualTo(ReviewReceipt.Status.CONSUMED);
        } else {
            assertThat(request.getStatus()).isEqualTo(ApprovalRequest.Status.INVALIDATED);
            assertThat(decisions.findByApprovalRequestId(fixture.requestId())).isEmpty();
            assertDecisionCount(fixture.requestId(), 0);
            assertThat(cases.findById(fixture.caseId()).orElseThrow().getStatus())
                    .isEqualTo(Case.Status.IN_REVIEW);
            assertThat(reports.findById(fixture.reportVersionId()).orElseThrow().getStatus())
                    .isEqualTo(ReportVersion.Status.INVALIDATED.name());
            assertAuditCount(fixture.requestId(), "APPROVAL_DECIDED", 0);
            assertAuditCount(fixture.requestId(), "APPROVAL_INVALIDATED", 1);
            assertThat(receipts.findById(receipt.reviewReceiptId()).orElseThrow().getStatus())
                    .isEqualTo(ReviewReceipt.Status.REVOKED);
        }
    }

    @Test
    void receiptReissueAndConsumptionRaceCannotUseTheSupersededToken() throws Exception {
        Fixture fixture = fixture();
        ApprovalReviewDto.Receipt receipt = receipt(fixture.approverOneSubject(), fixture.requestId());
        ApprovalReviewDto.DecisionInput decision = decision(fixture, receipt.reviewReceiptToken(),
                ApprovalDecision.Outcome.APPROVE, "Consume the receipt while a replacement races");

        List<String> outcomes = race(
                new Attempt(fixture.approverOneSubject(), () -> review.issueReceipt(fixture.requestId())
                        .status().name()),
                new Attempt(fixture.approverOneSubject(), () -> review.decide(fixture.requestId(), decision,
                        UUID.randomUUID().toString()).outcome().name()));

        assertThat(outcomes.stream().filter(value -> value.startsWith("OK:"))).hasSize(1);
        assertThat(outcomes.stream().filter(value -> !value.startsWith("OK:")))
                .allSatisfy(value -> assertThat(value).isIn("REVIEW_RECEIPT_ALREADY_USED",
                        "APPROVAL_REQUEST_CLOSED"));
        ApprovalRequest request = requests.findById(fixture.requestId()).orElseThrow();
        if (request.getStatus() == ApprovalRequest.Status.APPROVED) {
            assertDecisionTerminalState(fixture, request,
                    decisions.findByApprovalRequestId(fixture.requestId()).orElseThrow());
            assertAuditCount(fixture.requestId(), "APPROVAL_DECIDED", 1);
            assertThat(receipts.findById(receipt.reviewReceiptId()).orElseThrow().getStatus())
                    .isEqualTo(ReviewReceipt.Status.CONSUMED);
            assertThat(receipts.findAll().stream().filter(current -> fixture.requestId()
                    .equals(current.getApprovalRequestId()))
                    .filter(current -> current.getStatus() == ReviewReceipt.Status.CONSUMED)).hasSize(1);
            assertThat(receipts.findAll().stream().filter(current -> fixture.requestId()
                    .equals(current.getApprovalRequestId()))
                    .filter(current -> current.getStatus() == ReviewReceipt.Status.ISSUED)).isEmpty();
        } else {
            assertThat(request.getStatus()).isEqualTo(ApprovalRequest.Status.PENDING);
            assertThat(decisions.findByApprovalRequestId(fixture.requestId())).isEmpty();
            assertDecisionCount(fixture.requestId(), 0);
            assertThat(request.getActiveCaseId()).isEqualTo(fixture.caseId());
            assertThat(cases.findById(fixture.caseId()).orElseThrow().getStatus())
                    .isEqualTo(Case.Status.READY_FOR_APPROVAL);
            assertThat(reports.findById(fixture.reportVersionId()).orElseThrow().getStatus())
                    .isEqualTo(ReportVersion.Status.PENDING_APPROVAL.name());
            assertAuditCount(fixture.requestId(), "APPROVAL_DECIDED", 0);
            assertThat(receipts.findById(receipt.reviewReceiptId()).orElseThrow().getStatus())
                    .isEqualTo(ReviewReceipt.Status.REVOKED);
            assertThat(receipts.findAll().stream().filter(current -> fixture.requestId()
                    .equals(current.getApprovalRequestId()))
                    .filter(current -> current.getStatus() == ReviewReceipt.Status.ISSUED)).hasSize(1);
        }
    }

    @Test
    void decisionAuditFailureRollsBackReceiptConsumptionDecisionAndState() {
        Fixture fixture = fixture();
        ApprovalReviewDto.Receipt receipt = receipt(fixture.approverOneSubject(), fixture.requestId());
        Case before = cases.findById(fixture.caseId()).orElseThrow();
        String trigger = "td_w08b_decision_audit_failure";
        jdbc.execute("DROP TRIGGER IF EXISTS " + trigger);
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON approval_audit_events FOR EACH ROW "
                + "BEGIN IF NEW.case_id = '" + fixture.caseId() + "' AND "
                + "NEW.event_type = 'APPROVAL_DECIDED' THEN "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'synthetic W08b decision audit failure'; "
                + "END IF; END");
        try {
            authenticate(fixture.approverOneSubject());
            assertThatThrownBy(() -> review.decide(fixture.requestId(), decision(fixture,
                            receipt.reviewReceiptToken(), ApprovalDecision.Outcome.APPROVE,
                            "Force audit rollback of the synthetic decision"), UUID.randomUUID().toString()))
                    .isInstanceOf(DataAccessException.class);
            assertThat(cases.findById(fixture.caseId()).orElseThrow().getStatus())
                    .isEqualTo(Case.Status.READY_FOR_APPROVAL);
            assertThat(cases.findById(fixture.caseId()).orElseThrow().getCaseVersion())
                    .isEqualTo(before.getCaseVersion());
            assertThat(requests.findById(fixture.requestId()).orElseThrow().getStatus())
                    .isEqualTo(ApprovalRequest.Status.PENDING);
            assertThat(reports.findById(fixture.reportVersionId()).orElseThrow().getStatus())
                    .isEqualTo(ReportVersion.Status.PENDING_APPROVAL.name());
            assertThat(receipts.findById(receipt.reviewReceiptId()).orElseThrow().getStatus())
                    .isEqualTo(ReviewReceipt.Status.ISSUED);
            assertThat(decisions.findByApprovalRequestId(fixture.requestId())).isEmpty();
            assertDecisionCount(fixture.requestId(), 0);
            assertAuditCount(fixture.requestId(), "APPROVAL_DECIDED", 0);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS " + trigger);
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void policyVersionCommittedWhileWaitingForCaseLockBlocksDecisionAtReadCommitted() throws Exception {
        Fixture fixture = fixture();
        ApprovalReviewDto.Receipt receipt = receipt(fixture.approverOneSubject(), fixture.requestId());
        ApprovalReviewDto.DecisionInput command = decision(fixture, receipt.reviewReceiptToken(),
                ApprovalDecision.Outcome.APPROVE, "Block approval after the preservation policy changes");
        String key = UUID.randomUUID().toString();
        String keyDigest = CaseSnapshotManifest.sha256(key.toLowerCase());
        String requestDigest = CaseSnapshotManifest.sha256("policy-race-" + fixture.requestId());
        UUID approver = memberships.findBySubjectAndTenantId(fixture.approverOneSubject(), fixture.tenantId())
                .orElseThrow().getId();
        CountDownLatch caseLocked = new CountDownLatch(1);
        CountDownLatch scalarRead = new CountDownLatch(1);
        CountDownLatch caseLockAttempted = new CountDownLatch(1);
        CountDownLatch releaseCaseLock = new CountDownLatch(1);
        AtomicReference<String> isolation = new AtomicReference<>();
        var workers = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon(true).factory());
        Future<?> holder = workers.submit(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    cases.findByIdForUpdate(fixture.caseId()).orElseThrow();
                    caseLocked.countDown();
                    awaitLatch(releaseCaseLock, "Case lock release");
                }));
        Future<Throwable> attemptedDecision = null;
        try {
            assertThat(caseLocked.await(10, TimeUnit.SECONDS)).isTrue();
            doAnswer(invocation -> {
                Object result = invocation.callRealMethod();
                isolation.set(String.valueOf(entityManager.createNativeQuery("SELECT @@transaction_isolation")
                        .getSingleResult()));
                scalarRead.countDown();
                return result;
            }).when(requests).findCaseIdById(fixture.requestId());
            doAnswer(invocation -> {
                caseLockAttempted.countDown();
                return invocation.callRealMethod();
            }).when(cases).findByIdForUpdate(fixture.caseId());

            attemptedDecision = workers.submit(() -> {
                authenticate(fixture.approverOneSubject());
                try {
                    reviewWriter.decide(fixture.requestId(), command, approver,
                            CaseSnapshotManifest.sha256(receipt.reviewReceiptToken()), keyDigest, requestDigest);
                    return null;
                } catch (Throwable error) {
                    return error;
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });

            assertThat(scalarRead.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(caseLockAttempted.await(10, TimeUnit.SECONDS)).isTrue();
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            policies.saveAndFlush(new EvidencePreservationPolicy(fixture.policyId(), 2,
                    fixture.tenantId(), null, EvidencePreservationPolicy.Status.REVOKED,
                    now.minusDays(1), now.plusDays(20), 10, PROFILE,
                    EvidencePreservationPolicy.Mode.GOVERNANCE, "synthetic-w08b-mysql-authority",
                    now.minusHours(2), now.minusHours(1), now));
            releaseCaseLock.countDown();

            Throwable failure = attemptedDecision.get(30, TimeUnit.SECONDS);
            holder.get(30, TimeUnit.SECONDS);
            assertThat(isolation.get()).isEqualToIgnoringCase("READ-COMMITTED");
            assertThat(failure).isInstanceOf(CaseApiException.class);
            assertThat(((CaseApiException) failure).code()).isEqualTo("APPROVAL_BASIS_BLOCKED");
            assertThat(decisions.findByApprovalRequestId(fixture.requestId())).isEmpty();
            assertDecisionCount(fixture.requestId(), 0);
            assertThat(receipts.findById(receipt.reviewReceiptId()).orElseThrow().getStatus())
                    .isEqualTo(ReviewReceipt.Status.ISSUED);
            assertThat(cases.findById(fixture.caseId()).orElseThrow().getStatus())
                    .isEqualTo(Case.Status.READY_FOR_APPROVAL);
            assertThat(requests.findById(fixture.requestId()).orElseThrow().getStatus())
                    .isEqualTo(ApprovalRequest.Status.PENDING);
            assertThat(requests.findById(fixture.requestId()).orElseThrow().getActiveCaseId())
                    .isEqualTo(fixture.caseId());
            assertThat(reports.findById(fixture.reportVersionId()).orElseThrow().getStatus())
                    .isEqualTo(ReportVersion.Status.PENDING_APPROVAL.name());
            assertAuditCount(fixture.requestId(), "APPROVAL_DECIDED", 0);
            assertThat(idempotency.findByTenantIdAndActorMembershipIdAndOperationAndTargetCaseIdAndKeyDigest(
                    fixture.tenantId(), approver, "APPROVAL_DECISION", fixture.caseId(),
                    keyDigest)).isEmpty();
        } finally {
            releaseCaseLock.countDown();
            if (attemptedDecision != null) attemptedDecision.cancel(true);
            holder.cancel(true);
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            reset(cases, requests);
        }
    }

    private void assertDecisionTerminalState(Fixture fixture, ApprovalRequest request,
            ApprovalDecision decision) {
        assertDecisionCount(fixture.requestId(), 1);
        assertThat(request.getActiveCaseId()).isNull();
        if (decision.getOutcome() == ApprovalDecision.Outcome.APPROVE) {
            assertThat(request.getStatus()).isEqualTo(ApprovalRequest.Status.APPROVED);
            assertThat(cases.findById(fixture.caseId()).orElseThrow().getStatus())
                    .isEqualTo(Case.Status.APPROVED_FOR_REPORT);
            assertThat(reports.findById(fixture.reportVersionId()).orElseThrow().getStatus())
                    .isEqualTo(ReportVersion.Status.APPROVED.name());
        } else {
            assertThat(request.getStatus()).isEqualTo(ApprovalRequest.Status.REJECTED);
            assertThat(cases.findById(fixture.caseId()).orElseThrow().getStatus())
                    .isEqualTo(Case.Status.IN_REVIEW);
            assertThat(reports.findById(fixture.reportVersionId()).orElseThrow().getStatus())
                    .isEqualTo(ReportVersion.Status.REJECTED.name());
        }
    }

    private void assertDecisionCount(UUID requestId, int expected) {
        assertThat(decisions.findAll().stream()
                .filter(decision -> requestId.equals(decision.getApprovalRequestId()))).hasSize(expected);
    }

    private void assertAuditCount(UUID requestId, String eventType, int expected) {
        assertThat(audits.findByApprovalRequestIdOrderByOccurredAtAscIdAsc(requestId).stream()
                .filter(event -> eventType.equals(event.getEventType()))).hasSize(expected);
    }

    private Fixture fixture() {
        UUID tenant = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        UUID reviewer = UUID.randomUUID();
        UUID approverOne = UUID.randomUUID();
        UUID approverTwo = UUID.randomUUID();
        String reviewerSubject = "w08b-mysql-reviewer-" + reviewer;
        String approverOneSubject = "w08b-mysql-approver-" + approverOne;
        String approverTwoSubject = "w08b-mysql-approver-" + approverTwo;
        tenants.saveAndFlush(new Tenant(tenant, "Synthetic W08b MySQL tenant", AccessRecordStatus.ACTIVE));
        events.saveAndFlush(new TenantEvent(event, tenant, "w08b-mysql-" + event,
                "Synthetic W08b MySQL event", AccessRecordStatus.ACTIVE));
        member(reviewer, reviewerSubject, AdminRole.REVIEWER, tenant, event);
        member(approverOne, approverOneSubject, AdminRole.APPROVER, tenant, event);
        member(approverTwo, approverTwoSubject, AdminRole.APPROVER, tenant, event);

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        UUID fanReportId = UUID.randomUUID();
        FanReport fanReport = new FanReport(fanReportId, "FR-" + fanReportId, tenant, event,
                now.plusDays(30), null, FanReport.ReportType.OTHER, "Synthetic W08b MySQL evidence",
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
        authenticate(reviewerSubject);
        CaseDto.View opened = caseService.create(new CaseDto.Create(event, Case.Type.FAN_REPORT,
                List.of(), List.of(fanReportId), List.of(evidenceId),
                "Investigate concurrent W08b approval evidence", null, null), UUID.randomUUID().toString());
        CaseDto.View assigned = caseService.assign(opened.caseId(),
                new CaseDto.Assign(reviewer, opened.caseVersion(), "Assign W08b MySQL reviewer"),
                UUID.randomUUID().toString());
        CaseDto.View triaged = caseService.transition(opened.caseId(), new CaseDto.Transition(
                Case.Status.TRIAGED, assigned.caseVersion(), "Triage W08b MySQL Case",
                "Synthetic W08b classification", null, null, null, null, null),
                UUID.randomUUID().toString());
        CaseDto.View reviewing = caseService.transition(opened.caseId(), new CaseDto.Transition(
                Case.Status.IN_REVIEW, triaged.caseVersion(), "Begin W08b MySQL review",
                null, null, null, null, null, null), UUID.randomUUID().toString());
        CasePreparationDto.Snapshot snapshot = preparation.capture(opened.caseId(),
                new CasePreparationDto.Capture(reviewing.caseVersion(), "Capture W08b MySQL evidence"),
                UUID.randomUUID().toString());
        CasePreparationDto.Draft draft = preparation.draft(opened.caseId(), new CasePreparationDto.DraftInput(
                        cases.findById(opened.caseId()).orElseThrow().getCaseVersion(),
                        snapshot.evidenceSnapshotId(), "Synthetic W08b approval report",
                        "Synthetic W08b assessment for independent approval",
                        "Synthetic W08b limitations", "Synthetic W08b conclusion",
                        "Seal W08b approval report"), UUID.randomUUID().toString());
        UUID sourceId = sources.findByCaseIdOrderByLinkedAtAscIdAsc(opened.caseId()).stream()
                .filter(source -> source.getSourceType() == CaseSourceReference.Type.EVIDENCE_ASSET)
                .findFirst().orElseThrow().getId();
        EvidenceIntegrityDto.Verification verification = integrity.verify(opened.caseId(),
                new EvidenceIntegrityDto.VerifyInput(cases.findById(opened.caseId()).orElseThrow().getCaseVersion(),
                        snapshot.evidenceSnapshotId(), sourceId, evidenceId,
                        "Verify W08b MySQL approval bytes"), UUID.randomUUID().toString());
        UUID policyId = UUID.randomUUID();
        policies.saveAndFlush(new EvidencePreservationPolicy(policyId, 1, tenant, event,
                EvidencePreservationPolicy.Status.APPROVED, now.minusDays(1), now.plusDays(20),
                10, PROFILE, EvidencePreservationPolicy.Mode.GOVERNANCE,
                "synthetic-w08b-mysql-authority", now.minusHours(2), now.minusHours(1), null));
        given(preservationStorage.verifyLocked(anyString(), isNull(), eq(sha256), eq(4L), eq(MAX_BYTES)))
                .willReturn(new EvidencePreservationStorage.LockedObject("locked-" + evidenceId,
                        sha256, 4, EvidencePreservationPolicy.Mode.GOVERNANCE, now.plusDays(5)));
        preservation.preserve(opened.caseId(), new EvidencePreservationService.Command(
                cases.findById(opened.caseId()).orElseThrow().getCaseVersion(),
                snapshot.evidenceSnapshotId(), sourceId, evidenceId, verification.recordId(), policyId,
                "Verify W08b MySQL immutable evidence"), UUID.randomUUID().toString());
        ApprovalRequestDto.View approval = approvals.submit(opened.caseId(), new ApprovalRequestDto.Submit(
                        draft.reportVersionId(), snapshot.evidenceSnapshotId(),
                        cases.findById(opened.caseId()).orElseThrow().getCaseVersion(), draft.version(),
                        draft.contentSha256(), "Submit W08b concurrent independent approval"),
                UUID.randomUUID().toString());
        return new Fixture(tenant, policyId, opened.caseId(), snapshot.evidenceSnapshotId(), draft.reportVersionId(),
                draft.contentSha256(), approval.approvalRequestId(), reviewerSubject, approverOneSubject,
                approverTwoSubject);
    }

    private ApprovalReviewDto.Receipt receipt(String subject, UUID requestId) {
        authenticate(subject);
        return review.issueReceipt(requestId);
    }

    private ApprovalReviewDto.DecisionInput decision(Fixture fixture, String token,
            ApprovalDecision.Outcome outcome, String reason) {
        Case target = cases.findById(fixture.caseId()).orElseThrow();
        ApprovalRequest request = requests.findById(fixture.requestId()).orElseThrow();
        ReportVersion report = reports.findById(fixture.reportVersionId()).orElseThrow();
        return new ApprovalReviewDto.DecisionInput(outcome, reason, fixture.snapshotId(),
                snapshotSha256(fixture.snapshotId()), fixture.contentSha256(), token, true,
                target.getCaseVersion(), request.getVersion(), report.getVersion());
    }

    private ApprovalReviewDto.WithdrawInput withdraw(Fixture fixture, String reason) {
        Case target = cases.findById(fixture.caseId()).orElseThrow();
        ApprovalRequest request = requests.findById(fixture.requestId()).orElseThrow();
        ReportVersion report = reports.findById(fixture.reportVersionId()).orElseThrow();
        return new ApprovalReviewDto.WithdrawInput(reason, target.getCaseVersion(), request.getVersion(),
                report.getVersion());
    }

    private String snapshotSha256(UUID snapshotId) {
        return jdbc.queryForObject("SELECT manifest_sha256 FROM evidence_snapshots WHERE id=?", String.class,
                snapshotId.toString());
    }

    private void member(UUID id, String subject, AdminRole role, UUID tenant, UUID event) {
        memberships.saveAndFlush(new AdminMembershipEntity(id, tenant, subject, AccessRecordStatus.ACTIVE));
        roles.saveAndFlush(new MembershipRole(UUID.randomUUID(), id, role));
        grants.saveAndFlush(new MembershipEventGrant(UUID.randomUUID(), id, tenant, event));
    }

    private static void awaitLatch(CountDownLatch latch, String description) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for " + description);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for " + description, error);
        }
    }

    private static List<String> race(Attempt first, Attempt second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean run = new AtomicBoolean();
        List<Future<String>> futures = new ArrayList<>();
        var workers = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon(true).factory());
        try {
            for (Attempt attempt : List.of(first, second)) futures.add(workers.submit(() -> {
                try {
                    authenticate(attempt.subject());
                    ready.countDown();
                    start.await();
                    if (!run.get()) return "ABORTED";
                    return "OK:" + attempt.action().execute();
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
            List<String> outcomes = new ArrayList<>();
            for (Future<String> future : futures) outcomes.add(future.get(30, TimeUnit.SECONDS));
            return outcomes;
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

    private record Fixture(UUID tenantId, UUID policyId, UUID caseId, UUID snapshotId, UUID reportVersionId,
                           String contentSha256, UUID requestId, String reviewerSubject,
                           String approverOneSubject, String approverTwoSubject) { }
    private record Attempt(String subject, Action action) { }
    private interface Action { String execute(); }
}
