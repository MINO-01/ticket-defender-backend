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
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.reset;

/**
 * Full W09 flow against the disposable MySQL schema and the dedicated private, versioned CI bucket.
 * W06/W07 object adapters are mocked here because their real object-lock coverage belongs to W06/W07
 * suites; this test proves the official renderer, official S3 adapter, and issuance finalization together.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "ticket-defender.evidence-preservation.enabled=true",
        "ticket-defender.evidence-preservation.max-bytes=20971520",
        "ticket-defender.official-pdf.enabled=true",
        "ticket-defender.official-pdf.operational-mode=SYNTHETIC_ISOLATED",
        "ticket-defender.official-pdf.max-bytes=10485760"
})
@EnabledIfEnvironmentVariable(named = "TD_ISSUE46_MINIO_BUCKET", matches = "td-issue46-ci-official-pdf")
class CaseW09MinioMySqlTest {
    private static final String PROFILE = "W09_MINIO_MYSQL_LOCKED";
    private static final long MAX_BYTES = 20L * 1024 * 1024;

    @Autowired private CaseService caseService;
    @Autowired private CasePreparationService preparation;
    @Autowired private EvidenceIntegrityService integrity;
    @Autowired private EvidencePreservationService preservation;
    @Autowired private ApprovalRequestService approvals;
    @Autowired private ApprovalReviewService review;
    @Autowired private OfficialPdfIssuanceService official;
    @Autowired private CaseRepository cases;
    @Autowired private CaseSourceReferenceRepository sources;
    @Autowired private ReportVersionRepository reports;
    @Autowired private ApprovalRequestRepository requests;
    @Autowired private ApprovalDecisionRepository decisions;
    @Autowired private OfficialPdfIssuanceRepository issuances;
    @Autowired private OfficialPdfArtifactRepository artifacts;
    @Autowired private TenantRepository tenants;
    @Autowired private TenantEventRepository events;
    @Autowired private AdminMembershipRepository memberships;
    @Autowired private MembershipRoleRepository roles;
    @Autowired private MembershipEventGrantRepository grants;
    @Autowired private FanReportRepository fanReports;
    @Autowired private EvidenceAssetRepository assets;
    @Autowired private EvidencePreservationPolicyRepository policies;
    @MockitoBean private EvidenceObjectStorage objectStorage;
    @MockitoBean private EvidencePreservationStorage preservationStorage;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> required("TD_ISSUE32_MYSQL_URL"));
        registry.add("spring.datasource.username", () -> required("TD_ISSUE32_MYSQL_USER"));
        registry.add("spring.datasource.password", () -> required("TD_ISSUE32_MYSQL_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("ticket-defender.official-pdf.storage.endpoint", () -> required("TD_ISSUE32_MINIO_ENDPOINT"));
        registry.add("ticket-defender.official-pdf.storage.bucket", () -> required("TD_ISSUE46_MINIO_BUCKET"));
        registry.add("ticket-defender.official-pdf.storage.access-key", () -> required("TD_ISSUE46_MINIO_ACCESS_KEY"));
        registry.add("ticket-defender.official-pdf.storage.secret-key", () -> required("TD_ISSUE46_MINIO_SECRET_KEY"));
    }

    @BeforeEach
    void setup() {
        reset(objectStorage, preservationStorage);
        given(preservationStorage.profile()).willReturn(PROFILE);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void issuesAndDownloadsARealKoreanPdfWithOneImmutableArtifactInPrivateMinio() throws Exception {
        Fixture fixture = approvedFixture();
        authenticate(fixture.adminSubject());

        String idempotencyKey = UUID.randomUUID().toString();
        OfficialPdfDto.Issuance issued = official.request(fixture.decisionId(), idempotencyKey);
        OfficialPdfDto.Issuance replayed = official.request(fixture.decisionId(), idempotencyKey);

        assertThat(issued.status()).as("issuance failure code: %s", issued.lastFailureCode())
                .isEqualTo(OfficialPdfIssuance.Status.GENERATED);
        assertThat(replayed.officialPdfIssuanceId()).isEqualTo(issued.officialPdfIssuanceId());
        assertThat(issued.artifactId()).isNotNull();
        OfficialPdfArtifact artifact = artifacts.findByIssuanceId(issued.officialPdfIssuanceId()).orElseThrow();
        assertThat(artifact.getObjectVersion()).isNotBlank().isNotEqualTo("null");
        assertThat(artifact.getPdfSha256()).hasSize(64);
        assertThat(artifact.getPdfSize()).isPositive();
        assertThat(issuances.findByApprovalDecisionId(fixture.decisionId())).hasValueSatisfying(issuance ->
                assertThat(issuance.getStatus()).isEqualTo(OfficialPdfIssuance.Status.GENERATED));
        assertThat(reports.findById(fixture.reportVersionId()).orElseThrow().getStatus())
                .isEqualTo(ReportVersion.Status.APPROVED.name());

        OfficialPdfIssuanceService.Download downloaded = official.download(issued.officialPdfIssuanceId());
        assertThat(downloaded.bytes()).startsWith((byte) '%', (byte) 'P', (byte) 'D', (byte) 'F', (byte) '-');
        assertThat(downloaded.sha256()).isEqualTo(artifact.getPdfSha256());
        try (var document = Loader.loadPDF(downloaded.bytes())) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("공식", "검토", "보고서", "승인", "증거", "W06", "W07");
        }
    }

    private Fixture approvedFixture() {
        UUID tenant = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        UUID reviewer = UUID.randomUUID();
        String reviewerSubject = "w09-minio-reviewer-" + reviewer;
        String approverSubject = "w09-minio-approver-" + UUID.randomUUID();
        String adminSubject = "w09-minio-admin-" + UUID.randomUUID();
        tenants.saveAndFlush(new Tenant(tenant, "Synthetic W09 MinIO tenant", AccessRecordStatus.ACTIVE));
        events.saveAndFlush(new TenantEvent(event, tenant, "w09-minio-" + event,
                "Synthetic W09 MinIO event", AccessRecordStatus.ACTIVE));
        member(reviewer, reviewerSubject, AdminRole.REVIEWER, tenant, event);
        member(UUID.randomUUID(), approverSubject, AdminRole.APPROVER, tenant, event);
        member(UUID.randomUUID(), adminSubject, AdminRole.TENANT_ADMIN, tenant, event);

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        UUID fanReportId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        FanReport fanReport = new FanReport(fanReportId, "FR-" + fanReportId, tenant, event, now.plusDays(120),
                null, FanReport.ReportType.OTHER, "Synthetic W09 MinIO evidence", null, null, null, null, now,
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
                List.of(fanReportId), List.of(evidenceId), "Investigate synthetic W09 MinIO evidence", null, null),
                UUID.randomUUID().toString());
        CaseDto.View assigned = caseService.assign(opened.caseId(), new CaseDto.Assign(reviewer, opened.caseVersion(),
                "Assign synthetic reviewer"), UUID.randomUUID().toString());
        CaseDto.View triaged = caseService.transition(opened.caseId(), new CaseDto.Transition(Case.Status.TRIAGED,
                assigned.caseVersion(), "Triage synthetic Case", "Synthetic official PDF classification", null, null,
                null, null, null), UUID.randomUUID().toString());
        CaseDto.View reviewing = caseService.transition(opened.caseId(), new CaseDto.Transition(Case.Status.IN_REVIEW,
                triaged.caseVersion(), "Begin synthetic review", null, null, null, null, null, null),
                UUID.randomUUID().toString());
        CasePreparationDto.Snapshot snapshot = preparation.capture(opened.caseId(),
                new CasePreparationDto.Capture(reviewing.caseVersion(), "Capture W09 MinIO evidence"),
                UUID.randomUUID().toString());
        CasePreparationDto.Draft draft = preparation.draft(opened.caseId(), new CasePreparationDto.DraftInput(
                cases.findById(opened.caseId()).orElseThrow().getCaseVersion(), snapshot.evidenceSnapshotId(),
                "합성 공식 PDF 보고서", "합성 조사 우선순위 평가", "합성 한계 및 주의사항", "합성 결론",
                "Seal W09 MinIO report"), UUID.randomUUID().toString());
        UUID sourceId = sources.findByCaseIdOrderByLinkedAtAscIdAsc(opened.caseId()).stream()
                .filter(source -> source.getSourceType() == CaseSourceReference.Type.EVIDENCE_ASSET)
                .findFirst().orElseThrow().getId();
        EvidenceIntegrityDto.Verification verification = integrity.verify(opened.caseId(),
                new EvidenceIntegrityDto.VerifyInput(cases.findById(opened.caseId()).orElseThrow().getCaseVersion(),
                        snapshot.evidenceSnapshotId(), sourceId, evidenceId, "Verify W09 MinIO evidence"),
                UUID.randomUUID().toString());
        UUID policyId = UUID.randomUUID();
        policies.saveAndFlush(new EvidencePreservationPolicy(policyId, 1, tenant, event,
                EvidencePreservationPolicy.Status.APPROVED, now.minusDays(1), now.plusDays(90), 90, PROFILE,
                EvidencePreservationPolicy.Mode.GOVERNANCE, "synthetic-w09-minio-authority", now.minusHours(2),
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
                evidenceId, verification.recordId(), policyId, "Preserve W09 MinIO immutable evidence"),
                UUID.randomUUID().toString());
        ReportVersion report = reports.findById(draft.reportVersionId()).orElseThrow();
        approvals.submit(opened.caseId(), new ApprovalRequestDto.Submit(draft.reportVersionId(), snapshot.evidenceSnapshotId(),
                cases.findById(opened.caseId()).orElseThrow().getCaseVersion(), report.getVersion(),
                draft.contentSha256(), "Request approved W09 MinIO report"), UUID.randomUUID().toString());
        ApprovalRequest request = requests.findByReportVersionId(draft.reportVersionId()).orElseThrow();
        authenticate(approverSubject);
        ApprovalReviewDto.Receipt receipt = review.issueReceipt(request.getId());
        report = reports.findById(draft.reportVersionId()).orElseThrow();
        review.decide(request.getId(), new ApprovalReviewDto.DecisionInput(ApprovalDecision.Outcome.APPROVE,
                "Approve independently reviewed W09 MinIO package", snapshot.evidenceSnapshotId(),
                snapshot.manifestSha256(), draft.contentSha256(), receipt.reviewReceiptToken(), true,
                cases.findById(opened.caseId()).orElseThrow().getCaseVersion(), request.getVersion(), report.getVersion()),
                UUID.randomUUID().toString());
        ApprovalDecision decision = decisions.findByApprovalRequestId(request.getId()).orElseThrow();
        SecurityContextHolder.clearContext();
        return new Fixture(opened.caseId(), draft.reportVersionId(), decision.getId(), adminSubject);
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

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private record Fixture(UUID caseId, UUID reportVersionId, UUID decisionId, String adminSubject) { }
}
