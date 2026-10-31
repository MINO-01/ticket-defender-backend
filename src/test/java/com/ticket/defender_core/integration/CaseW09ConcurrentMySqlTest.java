package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.in.web.dto.ApprovalRequestDto;
import com.ticket.defender_core.adapter.in.web.dto.ApprovalReviewDto;
import com.ticket.defender_core.adapter.in.web.dto.CaseDto;
import com.ticket.defender_core.adapter.in.web.dto.CasePreparationDto;
import com.ticket.defender_core.adapter.in.web.dto.EvidenceIntegrityDto;
import com.ticket.defender_core.adapter.in.web.dto.OfficialPdfDto;
import com.ticket.defender_core.adapter.out.persistence.*;
import com.ticket.defender_core.application.service.*;
import com.ticket.defender_core.domain.*;
import com.ticket.defender_core.security.AdminRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.reset;

/**
 * Uses the disposable CI MySQL schema only.  Latches prove the interleavings; no timing sleep
 * is used for issuance or Hold ordering.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "ticket-defender.evidence-preservation.enabled=true",
        "ticket-defender.evidence-preservation.max-bytes=20971520",
        "ticket-defender.official-pdf.enabled=true",
        "ticket-defender.official-pdf.operational-mode=SYNTHETIC_ISOLATED"
})
@EnabledIfEnvironmentVariable(named = "TD_ISSUE32_MYSQL_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:13307/td_issue32.*")
class CaseW09ConcurrentMySqlTest {
    private static final String PROFILE = "W09_MYSQL_LOCKED";
    private static final long MAX_BYTES = 20L * 1024 * 1024;

    @Autowired private CaseService caseService;
    @Autowired private CasePreparationService preparation;
    @Autowired private EvidenceIntegrityService integrity;
    @Autowired private EvidencePreservationService preservation;
    @Autowired private ApprovalRequestService approvals;
    @Autowired private ApprovalReviewService review;
    @Autowired private OfficialPdfIssuanceService official;
    @Autowired private InternalIssuanceHoldWriteService holdWriter;
    @Autowired private CaseRepository cases;
    @Autowired private CaseSourceReferenceRepository sources;
    @Autowired private ReportVersionRepository reports;
    @Autowired private ApprovalRequestRepository requests;
    @Autowired private ReviewReceiptRepository receipts;
    @Autowired private ApprovalDecisionRepository decisions;
    @Autowired private OfficialPdfIssuanceRepository issuances;
    @Autowired private OfficialPdfArtifactRepository artifacts;
    @Autowired private OfficialPdfIssuanceAttemptRepository attempts;
    @Autowired private OfficialPdfAuditEventRepository audits;
    @Autowired private IssuanceHoldRepository holds;
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
    @MockitoBean private EvidenceObjectStorage objectStorage;
    @MockitoBean private EvidencePreservationStorage preservationStorage;
    @MockitoBean private OfficialPdfStorage officialStorage;
    @MockitoBean private OfficialPdfRenderer renderer;

    private final Map<UUID, Stored> stored = new ConcurrentHashMap<>();
    private volatile StorageGate storageGate = StorageGate.open();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TD_ISSUE32_MYSQL_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("TD_ISSUE32_MYSQL_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("TD_ISSUE32_MYSQL_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @BeforeEach
    void setupStorage() {
        reset(objectStorage, preservationStorage, officialStorage, renderer);
        stored.clear();
        storageGate = StorageGate.open();
        given(preservationStorage.profile()).willReturn(PROFILE);
        given(renderer.render(any())).willReturn(
                "%PDF-1.7\n% W09 synthetic concurrent PDF\n%%EOF\n".getBytes(StandardCharsets.US_ASCII));
        given(officialStorage.putIfAbsent(any(UUID.class), anyString(), any(byte[].class))).willAnswer(call -> {
            UUID issuanceId = call.getArgument(0);
            byte[] bytes = call.getArgument(2);
            StorageGate gate = storageGate;
            if (gate.blockFirst().compareAndSet(true, false)) {
                gate.entered().countDown();
                awaitLatch(gate.release(), "official PDF object-store release");
            }
            return stored.computeIfAbsent(issuanceId, ignored -> new Stored(bytes,
                    new OfficialPdfStorage.ObjectProof("mysql-v-" + issuanceId, sha256(bytes), bytes.length))).proof();
        });
        given(officialStorage.verify(any(UUID.class), anyString(), anyString(), anyString(), anyLong()))
                .willAnswer(call -> stored.get(call.getArgument(0)).proof());
        given(officialStorage.download(any(UUID.class), anyString(), anyString(), anyString(), anyLong()))
                .willAnswer(call -> {
                    Stored value = stored.get(call.getArgument(0));
                    return new OfficialPdfStorage.DownloadedPdf(value.bytes(), value.proof().sha256(), value.proof().size());
                });
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void concurrentRequestsCreateExactlyOneArtifactAndOneSuccessfulAttempt() throws Exception {
        Fixture fixture = approvedFixture();
        storageGate = StorageGate.blockFirstGate();
        var workers = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon(true).factory());
        Future<OfficialPdfDto.Issuance> first = workers.submit(() -> request(fixture.adminSubject(), fixture.decisionId()));
        Future<OfficialPdfDto.Issuance> second = null;
        try {
            assertThat(storageGate.entered().await(20, TimeUnit.SECONDS)).isTrue();
            second = workers.submit(() -> request(fixture.adminSubject(), fixture.decisionId()));
            OfficialPdfDto.Issuance waiting = second.get(20, TimeUnit.SECONDS);
            assertThat(waiting.status()).isEqualTo(OfficialPdfIssuance.Status.RENDERING);

            storageGate.release().countDown();
            OfficialPdfDto.Issuance generated = first.get(30, TimeUnit.SECONDS);
            assertThat(generated.status()).isEqualTo(OfficialPdfIssuance.Status.GENERATED);
            assertGeneratedInvariant(fixture, generated.officialPdfIssuanceId());
            assertThat(stored).hasSize(1);
            assertThat(attempts.findByIssuanceIdOrderByAttemptNumberAsc(generated.officialPdfIssuanceId()))
                    .singleElement().extracting(OfficialPdfIssuanceAttempt::getStatus)
                    .isEqualTo(OfficialPdfIssuanceAttempt.Status.SUCCEEDED);
        } finally {
            storageGate.release().countDown();
            first.cancel(true);
            if (second != null) second.cancel(true);
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void holdCommittedWhileObjectStoreIsPausedBlocksFinalizationAndLeavesNoArtifact() throws Exception {
        Fixture fixture = approvedFixture();
        storageGate = StorageGate.blockFirstGate();
        var workers = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon(true).factory());
        Future<OfficialPdfDto.Issuance> request = workers.submit(() -> request(fixture.adminSubject(), fixture.decisionId()));
        try {
            assertThat(storageGate.entered().await(20, TimeUnit.SECONDS)).isTrue();
            holdWriter.place(fixture.caseId(), fixture.reviewerId(), "SYNTHETIC_FINALIZATION_HOLD");
            storageGate.release().countDown();

            OfficialPdfDto.Issuance terminal = request.get(30, TimeUnit.SECONDS);
            assertThat(terminal.status()).isEqualTo(OfficialPdfIssuance.Status.FAILED);
            assertThat(terminal.lastFailureCode()).isEqualTo("ISSUANCE_HOLD_ACTIVE");
            OfficialPdfIssuance issuance = issuances.findById(terminal.officialPdfIssuanceId()).orElseThrow();
            assertThat(issuance.hasCandidateObject()).isTrue();
            assertThat(artifacts.findByIssuanceId(issuance.getId())).isEmpty();
            assertThat(holds.existsByTenantIdAndEventIdAndCaseIdAndStatus(fixture.tenantId(), fixture.eventId(),
                    fixture.caseId(), IssuanceHold.Status.ACTIVE)).isTrue();
            assertThat(attempts.findByIssuanceIdOrderByAttemptNumberAsc(issuance.getId()))
                    .singleElement().extracting(OfficialPdfIssuanceAttempt::getStatus)
                    .isEqualTo(OfficialPdfIssuanceAttempt.Status.FAILED);
            assertThat(audits.findByIssuanceIdOrderByOccurredAtAscIdAsc(issuance.getId()))
                    .extracting(OfficialPdfAuditEvent::getEventType)
                    .contains("ISSUANCE_RESERVED", "OBJECT_STORED", "ISSUANCE_FAILED")
                    .doesNotContain("ISSUANCE_GENERATED");
            assertApprovedHumanDecisionInvariant(fixture);
        } finally {
            storageGate.release().countDown();
            request.cancel(true);
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void policyChangeWhileReservationWaitsForCaseLockUsesReadCommittedAndBlocksIssuance() throws Exception {
        Fixture fixture = approvedFixture();
        CountDownLatch caseLocked = new CountDownLatch(1);
        CountDownLatch releaseCaseLock = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon(true).factory());
        Future<?> holder = workers.submit(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    cases.findByIdForUpdate(fixture.caseId()).orElseThrow();
                    caseLocked.countDown();
                    awaitLatch(releaseCaseLock, "Case lock release");
                }));
        Future<Throwable> attempted = null;
        try {
            assertThat(caseLocked.await(10, TimeUnit.SECONDS)).isTrue();
            attempted = workers.submit(() -> {
                authenticate(fixture.adminSubject());
                try {
                    official.request(fixture.decisionId(), UUID.randomUUID().toString());
                    return null;
                } catch (Throwable error) {
                    return error;
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
            String isolation = awaitWaitingCaseLockIsolation();
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            policies.saveAndFlush(new EvidencePreservationPolicy(fixture.policyId(), 2, fixture.tenantId(),
                    fixture.eventId(), EvidencePreservationPolicy.Status.REVOKED, now.minusDays(1), now.plusDays(20),
                    10, PROFILE, EvidencePreservationPolicy.Mode.GOVERNANCE, "synthetic-w09-mysql-authority",
                    now.minusHours(2), now.minusHours(1), now));
            releaseCaseLock.countDown();

            Throwable failure = attempted.get(30, TimeUnit.SECONDS);
            holder.get(30, TimeUnit.SECONDS);
            assertThat(isolation.replace(' ', '-')).isEqualToIgnoringCase("READ-COMMITTED");
            assertThat(failure).isInstanceOf(CaseApiException.class);
            assertThat(((CaseApiException) failure).code()).isEqualTo("APPROVAL_BASIS_BLOCKED");
            assertThat(issuances.findByApprovalDecisionId(fixture.decisionId())).isEmpty();
            assertThat(artifacts.findByApprovalDecisionId(fixture.decisionId())).isEmpty();
            assertApprovedHumanDecisionInvariant(fixture);
        } finally {
            releaseCaseLock.countDown();
            if (attempted != null) attempted.cancel(true);
            holder.cancel(true);
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private OfficialPdfDto.Issuance request(String subject, UUID decisionId) {
        authenticate(subject);
        try {
            return official.request(decisionId, UUID.randomUUID().toString());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void assertGeneratedInvariant(Fixture fixture, UUID issuanceId) {
        assertThat(issuances.findAll().stream().filter(value -> fixture.decisionId()
                .equals(value.getApprovalDecisionId()))).singleElement().extracting(OfficialPdfIssuance::getId)
                .isEqualTo(issuanceId);
        assertThat(artifacts.findAll().stream().filter(value -> fixture.decisionId()
                .equals(value.getApprovalDecisionId()))).singleElement().satisfies(artifact -> {
                    assertThat(artifact.getIssuanceId()).isEqualTo(issuanceId);
                    assertThat(artifact.getObjectVersion()).isNotBlank();
                    assertThat(artifact.getPdfSha256()).hasSize(64);
                });
        assertThat(audits.findByIssuanceIdOrderByOccurredAtAscIdAsc(issuanceId)).extracting(
                OfficialPdfAuditEvent::getEventType).contains("ISSUANCE_GENERATED");
        assertApprovedHumanDecisionInvariant(fixture);
    }

    private void assertApprovedHumanDecisionInvariant(Fixture fixture) {
        ApprovalDecision decision = decisions.findById(fixture.decisionId()).orElseThrow();
        ApprovalRequest request = requests.findById(decision.getApprovalRequestId()).orElseThrow();
        ReviewReceipt receipt = receipts.findById(decision.getReviewReceiptId()).orElseThrow();
        assertThat(decision.getOutcome()).isEqualTo(ApprovalDecision.Outcome.APPROVE);
        assertThat(request.getStatus()).isEqualTo(ApprovalRequest.Status.APPROVED);
        assertThat(request.getActiveCaseId()).isNull();
        assertThat(receipt.getStatus()).isEqualTo(ReviewReceipt.Status.CONSUMED);
        assertThat(cases.findById(fixture.caseId()).orElseThrow().getStatus())
                .isEqualTo(Case.Status.APPROVED_FOR_REPORT);
        assertThat(reports.findById(fixture.reportVersionId()).orElseThrow().getStatus())
                .isEqualTo(ReportVersion.Status.APPROVED.name());
        assertThat(decisions.findAll().stream().filter(value -> request.getId()
                .equals(value.getApprovalRequestId()))).hasSize(1);
        assertThat(receipts.findAll().stream().filter(value -> request.getId().equals(value.getApprovalRequestId()))
                .filter(value -> value.getStatus() == ReviewReceipt.Status.CONSUMED)).hasSize(1);
    }

    private Fixture approvedFixture() {
        UUID tenant = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        UUID reviewer = UUID.randomUUID();
        String reviewerSubject = "w09-mysql-reviewer-" + reviewer;
        String approverSubject = "w09-mysql-approver-" + UUID.randomUUID();
        String adminSubject = "w09-mysql-admin-" + UUID.randomUUID();
        tenants.saveAndFlush(new Tenant(tenant, "Synthetic W09 MySQL tenant", AccessRecordStatus.ACTIVE));
        events.saveAndFlush(new TenantEvent(event, tenant, "w09-mysql-" + event,
                "Synthetic W09 MySQL event", AccessRecordStatus.ACTIVE));
        member(reviewer, reviewerSubject, AdminRole.REVIEWER, tenant, event);
        member(UUID.randomUUID(), approverSubject, AdminRole.APPROVER, tenant, event);
        member(UUID.randomUUID(), adminSubject, AdminRole.TENANT_ADMIN, tenant, event);

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        UUID fanReportId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        FanReport fanReport = new FanReport(fanReportId, "FR-" + fanReportId, tenant, event, now.plusDays(120),
                null, FanReport.ReportType.OTHER, "Synthetic W09 MySQL evidence", null, null, null, null, now,
                UUID.randomUUID().toString(), "d".repeat(64), "e".repeat(64));
        fanReport.markReceived();
        fanReports.saveAndFlush(fanReport);
        String objectKey = "fan-reports/" + fanReportId + "/" + evidenceId;
        String sha256 = "a".repeat(64);
        EvidenceAsset asset = new EvidenceAsset(evidenceId, fanReportId, objectKey, "image/png", 4, sha256,
                now, fanReport.getRetentionUntil());
        asset.markStored("etag-" + evidenceId);
        assets.saveAndFlush(asset);
        given(objectStorage.head(objectKey)).willReturn(new EvidenceObjectStorage.ObjectMetadata(4, "image/png",
                asset.getObjectVersion()));
        given(objectStorage.verifyBytes(objectKey, asset.getObjectVersion(), MAX_BYTES)).willReturn(
                new EvidenceObjectStorage.ByteVerification(4, "image/png", sha256, asset.getObjectVersion(),
                        EvidenceObjectStorage.MarkerKind.ETAG));

        authenticate(reviewerSubject);
        CaseDto.View opened = caseService.create(new CaseDto.Create(event, Case.Type.FAN_REPORT, List.of(),
                List.of(fanReportId), List.of(evidenceId), "Investigate synthetic W09 concurrency", null, null),
                UUID.randomUUID().toString());
        CaseDto.View assigned = caseService.assign(opened.caseId(), new CaseDto.Assign(reviewer, opened.caseVersion(),
                "Assign synthetic W09 reviewer"), UUID.randomUUID().toString());
        CaseDto.View triaged = caseService.transition(opened.caseId(), new CaseDto.Transition(Case.Status.TRIAGED,
                assigned.caseVersion(), "Triage synthetic W09 case", "Synthetic W09 classification", null, null,
                null, null, null), UUID.randomUUID().toString());
        CaseDto.View reviewing = caseService.transition(opened.caseId(), new CaseDto.Transition(Case.Status.IN_REVIEW,
                triaged.caseVersion(), "Begin synthetic W09 review", null, null, null, null, null, null),
                UUID.randomUUID().toString());
        CasePreparationDto.Snapshot snapshot = preparation.capture(opened.caseId(),
                new CasePreparationDto.Capture(reviewing.caseVersion(), "Capture W09 MySQL evidence"),
                UUID.randomUUID().toString());
        CasePreparationDto.Draft draft = preparation.draft(opened.caseId(), new CasePreparationDto.DraftInput(
                cases.findById(opened.caseId()).orElseThrow().getCaseVersion(), snapshot.evidenceSnapshotId(),
                "Synthetic W09 approval report", "Synthetic W09 assessment", "Synthetic W09 limitations",
                "Synthetic W09 conclusion", "Seal W09 report"), UUID.randomUUID().toString());
        UUID sourceId = sources.findByCaseIdOrderByLinkedAtAscIdAsc(opened.caseId()).stream()
                .filter(source -> source.getSourceType() == CaseSourceReference.Type.EVIDENCE_ASSET)
                .findFirst().orElseThrow().getId();
        EvidenceIntegrityDto.Verification verification = integrity.verify(opened.caseId(),
                new EvidenceIntegrityDto.VerifyInput(cases.findById(opened.caseId()).orElseThrow().getCaseVersion(),
                        snapshot.evidenceSnapshotId(), sourceId, evidenceId, "Verify W09 MySQL evidence"),
                UUID.randomUUID().toString());
        UUID policyId = UUID.randomUUID();
        policies.saveAndFlush(new EvidencePreservationPolicy(policyId, 1, tenant, event,
                EvidencePreservationPolicy.Status.APPROVED, now.minusDays(1), now.plusDays(90), 90, PROFILE,
                EvidencePreservationPolicy.Mode.GOVERNANCE, "synthetic-w09-mysql-authority", now.minusHours(2),
                now.minusHours(1), null));
        given(preservationStorage.verifyLocked(anyString(), nullable(String.class), eq(sha256), eq(4L), eq(MAX_BYTES)))
                .willAnswer(call -> {
                    String requestedVersion = call.getArgument(1, String.class);
                    return new EvidencePreservationStorage.LockedObject(
                            requestedVersion == null ? "locked-" + evidenceId : requestedVersion, sha256, 4,
                            EvidencePreservationPolicy.Mode.GOVERNANCE,
                            now.plusDays(60).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
                });
        preservation.preserve(opened.caseId(), new EvidencePreservationService.Command(
                cases.findById(opened.caseId()).orElseThrow().getCaseVersion(), snapshot.evidenceSnapshotId(), sourceId,
                evidenceId, verification.recordId(), policyId, "Preserve W09 MySQL immutable evidence"),
                UUID.randomUUID().toString());
        ReportVersion report = reports.findById(draft.reportVersionId()).orElseThrow();
        approvals.submit(opened.caseId(), new ApprovalRequestDto.Submit(draft.reportVersionId(), snapshot.evidenceSnapshotId(),
                cases.findById(opened.caseId()).orElseThrow().getCaseVersion(), report.getVersion(),
                draft.contentSha256(), "Request approved W09 MySQL report"), UUID.randomUUID().toString());
        ApprovalRequest request = requests.findByReportVersionId(draft.reportVersionId()).orElseThrow();
        authenticate(approverSubject);
        ApprovalReviewDto.Receipt receipt = review.issueReceipt(request.getId());
        report = reports.findById(draft.reportVersionId()).orElseThrow();
        review.decide(request.getId(), new ApprovalReviewDto.DecisionInput(ApprovalDecision.Outcome.APPROVE,
                "Approve independently reviewed W09 MySQL package", snapshot.evidenceSnapshotId(),
                snapshot.manifestSha256(), draft.contentSha256(), receipt.reviewReceiptToken(), true,
                cases.findById(opened.caseId()).orElseThrow().getCaseVersion(), request.getVersion(), report.getVersion()),
                UUID.randomUUID().toString());
        ApprovalDecision decision = decisions.findByApprovalRequestId(request.getId()).orElseThrow();
        SecurityContextHolder.clearContext();
        return new Fixture(tenant, event, policyId, reviewer, opened.caseId(), draft.reportVersionId(), decision.getId(),
                adminSubject);
    }

    private String awaitWaitingCaseLockIsolation() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            List<String> isolations = jdbc.query("""
                    SELECT transaction_row.trx_isolation_level
                    FROM performance_schema.data_lock_waits lock_wait
                    JOIN performance_schema.data_locks requested_lock
                      ON requested_lock.engine = lock_wait.engine
                     AND requested_lock.engine_lock_id = lock_wait.requesting_engine_lock_id
                    JOIN performance_schema.threads waiting_thread
                      ON waiting_thread.thread_id = lock_wait.requesting_thread_id
                    JOIN information_schema.innodb_trx transaction_row
                      ON transaction_row.trx_mysql_thread_id = waiting_thread.processlist_id
                    WHERE requested_lock.object_schema = DATABASE()
                      AND requested_lock.object_name = 'cases'
                      AND requested_lock.lock_status = 'WAITING'
                    """, (resultSet, rowNum) -> resultSet.getString(1));
            if (!isolations.isEmpty()) return isolations.getFirst();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        throw new IllegalStateException("Timed out waiting for the W09 issuance transaction to block on Case");
    }

    private void member(UUID id, String subject, AdminRole role, UUID tenant, UUID event) {
        memberships.saveAndFlush(new AdminMembershipEntity(id, tenant, subject, AccessRecordStatus.ACTIVE));
        roles.saveAndFlush(new MembershipRole(UUID.randomUUID(), id, role));
        grants.saveAndFlush(new MembershipEventGrant(UUID.randomUUID(), id, tenant, event));
    }

    private static void authenticate(String subject) {
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(Jwt.withTokenValue(subject)
                .header("alg", "RS256").subject(subject).build());
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private static void awaitLatch(CountDownLatch latch, String description) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting for " + description);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for " + description, error);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Fixture(UUID tenantId, UUID eventId, UUID policyId, UUID reviewerId, UUID caseId,
                           UUID reportVersionId, UUID decisionId, String adminSubject) { }
    private record Stored(byte[] bytes, OfficialPdfStorage.ObjectProof proof) { }
    private record StorageGate(CountDownLatch entered, CountDownLatch release, AtomicBoolean blockFirst) {
        static StorageGate open() { return new StorageGate(new CountDownLatch(0), new CountDownLatch(0), new AtomicBoolean()); }
        static StorageGate blockFirstGate() { return new StorageGate(new CountDownLatch(1), new CountDownLatch(1), new AtomicBoolean(true)); }
    }
}
