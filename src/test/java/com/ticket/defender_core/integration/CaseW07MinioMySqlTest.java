package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.in.web.dto.ApprovalRequestDto;
import com.ticket.defender_core.adapter.in.web.dto.CaseDto;
import com.ticket.defender_core.adapter.in.web.dto.CasePreparationDto;
import com.ticket.defender_core.adapter.in.web.dto.EvidenceIntegrityDto;
import com.ticket.defender_core.adapter.out.persistence.*;
import com.ticket.defender_core.application.service.*;
import com.ticket.defender_core.domain.*;
import com.ticket.defender_core.security.AdminRole;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.net.URI;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "ticket-defender.evidence-preservation.enabled=true"
})
@EnabledIfEnvironmentVariable(named = "TD_ISSUE40_MINIO_BUCKET",
        matches = "td-issue40-ci-preservation")
class CaseW07MinioMySqlTest {
    @Autowired private CaseService caseService;
    @Autowired private CasePreparationService preparation;
    @Autowired private EvidenceIntegrityService integrity;
    @Autowired private EvidencePreservationService preservation;
    @Autowired private ApprovalRequestService approvals;
    @Autowired private TenantRepository tenants;
    @Autowired private TenantEventRepository events;
    @Autowired private AdminMembershipRepository memberships;
    @Autowired private MembershipRoleRepository roles;
    @Autowired private MembershipEventGrantRepository grants;
    @Autowired private FanReportRepository reports;
    @Autowired private EvidenceAssetRepository assets;
    @Autowired private CaseRepository cases;
    @Autowired private CaseSourceReferenceRepository sources;
    @Autowired private EvidencePreservationPolicyRepository policies;
    @Autowired private EvidencePreservationRecordRepository preservationRecords;
    @Autowired private EvidencePreservationAttemptRepository attempts;
    @Autowired private ApprovalRequestRepository approvalRequests;
    @Autowired private ReportVersionRepository reportVersions;
    @MockitoBean private EvidenceObjectStorage objectStorage;

    private final List<String> keys = new ArrayList<>();
    private S3Client admin;
    private String bucket;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> required("TD_ISSUE32_MYSQL_URL"));
        registry.add("spring.datasource.username", () -> required("TD_ISSUE32_MYSQL_USER"));
        registry.add("spring.datasource.password", () -> required("TD_ISSUE32_MYSQL_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("ticket-defender.evidence-preservation.storage.profile",
                () -> required("TD_ISSUE40_MINIO_PROFILE"));
        registry.add("ticket-defender.evidence-preservation.storage.endpoint",
                () -> required("TD_ISSUE32_MINIO_ENDPOINT"));
        registry.add("ticket-defender.evidence-preservation.storage.bucket",
                () -> required("TD_ISSUE40_MINIO_BUCKET"));
        registry.add("ticket-defender.evidence-preservation.storage.access-key",
                () -> required("TD_ISSUE40_MINIO_VERIFIER_ACCESS_KEY"));
        registry.add("ticket-defender.evidence-preservation.storage.secret-key",
                () -> required("TD_ISSUE40_MINIO_VERIFIER_SECRET_KEY"));
    }

    @BeforeAll
    void connect() {
        bucket = required("TD_ISSUE40_MINIO_BUCKET");
        admin = S3Client.builder().endpointOverride(URI.create(required("TD_ISSUE32_MINIO_ENDPOINT")))
                .region(Region.US_EAST_1).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        required("TD_ISSUE32_MINIO_ADMIN_ACCESS_KEY"),
                        required("TD_ISSUE32_MINIO_ADMIN_SECRET_KEY")))).build();
    }

    @AfterEach
    void cleanup() {
        for (String key : keys) {
            ListObjectVersionsResponse versions = admin.listObjectVersions(
                    ListObjectVersionsRequest.builder().bucket(bucket).prefix(key).build());
            versions.deleteMarkers().forEach(marker -> admin.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket).key(marker.key()).versionId(marker.versionId()).build()));
            versions.versions().forEach(version -> admin.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket).key(version.key()).versionId(version.versionId())
                    .bypassGovernanceRetention(true).build()));
        }
        keys.clear();
        SecurityContextHolder.clearContext();
    }

    @AfterAll
    void close() {
        if (admin != null) admin.close();
    }

    @Test
    void realLockedSyntheticObjectCreatesPreservationProofAndPinsApprovalRequest() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        String subject = "w07-minio-reviewer-" + member;
        tenants.saveAndFlush(new Tenant(tenant, "Synthetic W07 tenant", AccessRecordStatus.ACTIVE));
        events.saveAndFlush(new TenantEvent(event, tenant, "w07-minio-" + event,
                "Synthetic W07 event", AccessRecordStatus.ACTIVE));
        memberships.saveAndFlush(new AdminMembershipEntity(member, tenant, subject, AccessRecordStatus.ACTIVE));
        roles.saveAndFlush(new MembershipRole(UUID.randomUUID(), member, AdminRole.REVIEWER));
        grants.saveAndFlush(new MembershipEventGrant(UUID.randomUUID(), member, tenant, event));
        UUID reportId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        FanReport report = new FanReport(reportId, "FR-" + reportId, tenant, event, now.plusDays(1), null,
                FanReport.ReportType.OTHER, "Synthetic W07 evidence", null, null, null, null,
                now, UUID.randomUUID().toString(), "d".repeat(64), "e".repeat(64));
        report.markReceived();
        reports.saveAndFlush(report);
        byte[] bytes = new byte[]{1, 2, 3, 4};
        String digest = sha256(bytes);
        UUID evidenceId = UUID.randomUUID();
        String sourceKey = "fan-reports/" + reportId + "/" + evidenceId;
        EvidenceAsset asset = new EvidenceAsset(evidenceId, reportId, sourceKey, "image/png",
                bytes.length, digest, now, report.getRetentionUntil());
        asset.markStored("etag");
        assets.saveAndFlush(asset);
        when(objectStorage.head(sourceKey)).thenReturn(
                new EvidenceObjectStorage.ObjectMetadata(bytes.length, "image/png", "etag"));
        when(objectStorage.verifyBytes(sourceKey, "etag", 20L * 1024 * 1024)).thenReturn(
                new EvidenceObjectStorage.ByteVerification(bytes.length, "image/png", digest, "etag",
                        EvidenceObjectStorage.MarkerKind.ETAG));
        authenticate(subject);
        CaseDto.View opened = caseService.create(new CaseDto.Create(event, Case.Type.FAN_REPORT,
                List.of(), List.of(reportId), List.of(evidenceId), "Investigate synthetic report", null, null),
                UUID.randomUUID().toString());
        CaseDto.View assigned = caseService.assign(opened.caseId(),
                new CaseDto.Assign(member, opened.caseVersion(), "Assign reviewer"),
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
        var draft = preparation.draft(opened.caseId(), new CasePreparationDto.DraftInput(
                        cases.findById(opened.caseId()).orElseThrow().getCaseVersion(),
                        snapshot.evidenceSnapshotId(), "Synthetic locked evidence report",
                        "The synthetic source bytes and immutable version are verified for review.",
                        "This fixture proves only the isolated technical preservation controls.",
                        "Bind this synthetic report and evidence for an approval request.",
                        "Create the W08a report revision"), UUID.randomUUID().toString());
        UUID sourceId = sources.findByCaseIdOrderByLinkedAtAscIdAsc(opened.caseId()).stream()
                .filter(source -> source.getSourceType() == CaseSourceReference.Type.EVIDENCE_ASSET)
                .findFirst().orElseThrow().getId();
        EvidenceIntegrityDto.Verification verification = integrity.verify(opened.caseId(),
                new EvidenceIntegrityDto.VerifyInput(
                        cases.findById(opened.caseId()).orElseThrow().getCaseVersion(),
                        snapshot.evidenceSnapshotId(), sourceId, evidenceId, "Verify synthetic bytes"),
                UUID.randomUUID().toString());
        UUID policyId = UUID.randomUUID();
        policies.saveAndFlush(new EvidencePreservationPolicy(policyId, 1, tenant, event,
                EvidencePreservationPolicy.Status.APPROVED, now.minusHours(1), now.plusDays(1),
                1, required("TD_ISSUE40_MINIO_PROFILE"), EvidencePreservationPolicy.Mode.GOVERNANCE,
                "synthetic-ci-authority", now.minusHours(2), now.minusHours(1), null));

        String preservationKey = "preserved-evidence/" + tenant + "/" + event + "/"
                + opened.caseId() + "/" + evidenceId;
        keys.add(preservationKey);
        Instant retainUntil = Instant.now().plusSeconds(180);
        String versionId = admin.putObject(PutObjectRequest.builder().bucket(bucket).key(preservationKey)
                        .contentType("image/png").objectLockMode(ObjectLockMode.GOVERNANCE)
                        .objectLockRetainUntilDate(retainUntil).build(), RequestBody.fromBytes(bytes))
                .versionId();

        EvidencePreservationService.Result result = preservation.preserve(opened.caseId(),
                new EvidencePreservationService.Command(
                        cases.findById(opened.caseId()).orElseThrow().getCaseVersion(),
                        snapshot.evidenceSnapshotId(), sourceId, evidenceId, verification.recordId(), policyId,
                        "Verify synthetic immutable version"), UUID.randomUUID().toString());
        ApprovalRequestDto.View approval = approvals.submit(opened.caseId(),
                new ApprovalRequestDto.Submit(draft.reportVersionId(), snapshot.evidenceSnapshotId(),
                        cases.findById(opened.caseId()).orElseThrow().getCaseVersion(), draft.version(),
                        draft.contentSha256(), "Submit the real locked evidence proof for review"),
                UUID.randomUUID().toString());

        assertThat(result.actualVersionId()).isEqualTo(versionId);
        assertThat(result.objectLockMode()).isEqualTo(EvidencePreservationPolicy.Mode.GOVERNANCE);
        assertThat(result.preservationStatus()).isEqualTo(EvidencePreservationRecord.Status.IMMUTABLE_VERIFIED);
        assertThat(preservationRecords.findById(result.recordId())).isPresent();
        assertThat(attempts.findByCaseIdOrderByAttemptedAtAscIdAsc(opened.caseId()))
                .singleElement().extracting(EvidencePreservationAttempt::getOutcome)
                .isEqualTo(EvidencePreservationAttempt.Outcome.VERIFIED);
        assertThat(approval.status()).isEqualTo(ApprovalRequest.Status.PENDING);
        assertThat(approval.approvalEligibility()).isEqualTo("BLOCKED");
        assertThat(approval.reportVersionId()).isEqualTo(draft.reportVersionId());
        assertThat(approval.evidenceSnapshotId()).isEqualTo(snapshot.evidenceSnapshotId());
        assertThat(approvalRequests.findById(approval.approvalRequestId())).isPresent();
        assertThat(cases.findById(opened.caseId()).orElseThrow().getStatus())
                .isEqualTo(Case.Status.READY_FOR_APPROVAL);
        assertThat(reportVersions.findById(draft.reportVersionId()).orElseThrow().getStatus())
                .isEqualTo(ReportVersion.Status.PENDING_APPROVAL.name());
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void authenticate(String subject) {
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(
                Jwt.withTokenValue("synthetic").header("alg", "RS256").subject(subject).build());
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
