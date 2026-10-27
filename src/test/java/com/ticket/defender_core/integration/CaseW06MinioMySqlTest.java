package com.ticket.defender_core.integration;

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
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "ticket-defender.fan-report.hmac-key-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
@EnabledIfEnvironmentVariable(named = "TD_ISSUE32_MINIO_ENDPOINT", matches = "http://127\\.0\\.0\\.1:19000")
class CaseW06MinioMySqlTest {
    @Autowired private CaseService caseService;
    @Autowired private CasePreparationService preparation;
    @Autowired private EvidenceIntegrityService integrity;
    @Autowired private EvidenceObjectStorage storage;
    @Autowired private TenantRepository tenants;
    @Autowired private TenantEventRepository events;
    @Autowired private AdminMembershipRepository memberships;
    @Autowired private MembershipRoleRepository roles;
    @Autowired private MembershipEventGrantRepository grants;
    @Autowired private FanReportRepository reports;
    @Autowired private EvidenceAssetRepository assets;
    @Autowired private CaseRepository cases;
    @Autowired private CaseSourceReferenceRepository sources;
    @Autowired private EvidenceVerificationRecordRepository records;

    private final List<String> keys = new ArrayList<>();
    private S3Client admin;
    private String bucket;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> required("TD_ISSUE32_MYSQL_URL"));
        registry.add("spring.datasource.username", () -> required("TD_ISSUE32_MYSQL_USER"));
        registry.add("spring.datasource.password", () -> required("TD_ISSUE32_MYSQL_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("ticket-defender.fan-report.enabled", () -> true);
        registry.add("ticket-defender.fan-report.storage.endpoint", () -> required("TD_ISSUE32_MINIO_ENDPOINT"));
        registry.add("ticket-defender.fan-report.storage.bucket", () -> required("TD_ISSUE32_MINIO_BUCKET"));
        registry.add("ticket-defender.fan-report.storage.access-key", () -> required("TD_ISSUE32_MINIO_ACCESS_KEY"));
        registry.add("ticket-defender.fan-report.storage.secret-key", () -> required("TD_ISSUE32_MINIO_SECRET_KEY"));
        registry.add("ticket-defender.fan-report.storage.head-access-key",
                () -> required("TD_ISSUE32_MINIO_HEAD_ACCESS_KEY"));
        registry.add("ticket-defender.fan-report.storage.head-secret-key",
                () -> required("TD_ISSUE32_MINIO_HEAD_SECRET_KEY"));
    }

    @BeforeAll
    void connect() {
        bucket = required("TD_ISSUE32_MINIO_BUCKET");
        admin = S3Client.builder().endpointOverride(URI.create(required("TD_ISSUE32_MINIO_ENDPOINT")))
                .region(Region.US_EAST_1).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        required("TD_ISSUE32_MINIO_ADMIN_ACCESS_KEY"),
                        required("TD_ISSUE32_MINIO_ADMIN_SECRET_KEY")))).build();
    }

    @AfterEach
    void cleanup() {
        for (String key : keys) admin.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        keys.clear();
        SecurityContextHolder.clearContext();
    }

    @AfterAll
    void close() { if (admin != null) admin.close(); }

    @Test
    void realMinioByteCheckDetectsReplacementAndKeepsSeparateHistory() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        String subject = "w06-minio-reviewer-" + member;
        tenants.saveAndFlush(new Tenant(tenant, "Synthetic W06 tenant", AccessRecordStatus.ACTIVE));
        events.saveAndFlush(new TenantEvent(event, tenant, "synthetic-show", "Synthetic show",
                AccessRecordStatus.ACTIVE));
        memberships.saveAndFlush(new AdminMembershipEntity(member, tenant, subject, AccessRecordStatus.ACTIVE));
        roles.saveAndFlush(new MembershipRole(UUID.randomUUID(), member, AdminRole.REVIEWER));
        grants.saveAndFlush(new MembershipEventGrant(UUID.randomUUID(), member, tenant, event));
        UUID reportId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        FanReport report = new FanReport(reportId, "FR-" + reportId, tenant, event, now.plusDays(1), null,
                FanReport.ReportType.OTHER, "Synthetic image", null, null, null, null,
                now, UUID.randomUUID().toString(), "d".repeat(64), "e".repeat(64));
        report.markReceived();
        reports.saveAndFlush(report);
        UUID evidenceId = UUID.randomUUID();
        String key = "fan-reports/" + reportId + "/" + evidenceId;
        byte[] original = image(0x00335577);
        String digest = sha256(original);
        String marker = storage.put(key, new ValidatedImage(original, "image/png", original.length, digest));
        keys.add(key);
        EvidenceAsset asset = new EvidenceAsset(evidenceId, reportId, key, "image/png", original.length,
                digest, now, report.getRetentionUntil());
        asset.markStored(marker);
        assets.saveAndFlush(asset);
        authenticate(subject);
        CaseDto.View opened = caseService.create(new CaseDto.Create(event, Case.Type.FAN_REPORT,
                List.of(), List.of(reportId), List.of(evidenceId), "Investigate synthetic report", null, null),
                UUID.randomUUID().toString());
        CaseDto.View assigned = caseService.assign(opened.caseId(), new CaseDto.Assign(member,
                opened.caseVersion(), "Assign reviewer"), UUID.randomUUID().toString());
        CaseDto.View triaged = caseService.transition(opened.caseId(),
                new CaseDto.Transition(Case.Status.TRIAGED, assigned.caseVersion(), "Triage case",
                        "Synthetic classification", null, null, null, null, null), UUID.randomUUID().toString());
        CaseDto.View reviewing = caseService.transition(opened.caseId(),
                new CaseDto.Transition(Case.Status.IN_REVIEW, triaged.caseVersion(), "Begin review",
                        null, null, null, null, null, null), UUID.randomUUID().toString());
        var snapshot = preparation.capture(reviewing.caseId(),
                new CasePreparationDto.Capture(reviewing.caseVersion(), "Capture source"),
                UUID.randomUUID().toString());
        UUID sourceId = sources.findByCaseIdOrderByLinkedAtAscIdAsc(reviewing.caseId()).stream()
                .filter(ref -> ref.getSourceType() == CaseSourceReference.Type.EVIDENCE_ASSET)
                .findFirst().orElseThrow().getId();
        var first = integrity.verify(reviewing.caseId(), new EvidenceIntegrityDto.VerifyInput(
                cases.findById(reviewing.caseId()).orElseThrow().getCaseVersion(), snapshot.evidenceSnapshotId(),
                sourceId, evidenceId, "Check original bytes"), UUID.randomUUID().toString());
        assertThat(first.verificationStatus()).isEqualTo(EvidenceVerificationRecord.Status.MATCH);
        assertThat(first.preservationStatus()).isEqualTo(EvidenceVerificationRecord.Preservation.UNPROTECTED);
        assertThat(first.markerKind()).isEqualTo(EvidenceVerificationRecord.MarkerKind.ETAG);
        assertThat(records.findById(first.recordId()).orElseThrow().getVerifiedSha256()).isEqualTo(digest);

        admin.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        byte[] replacement = image(0x00aa5533);
        admin.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType("image/png").build(),
                RequestBody.fromBytes(replacement));
        assertThatThrownBy(() -> integrity.verify(reviewing.caseId(), new EvidenceIntegrityDto.VerifyInput(
                cases.findById(reviewing.caseId()).orElseThrow().getCaseVersion(), snapshot.evidenceSnapshotId(),
                sourceId, evidenceId, "Check replaced bytes"), UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("OBJECT_CHANGED"));
        assertThat(records.findByCaseId(reviewing.caseId(), org.springframework.data.domain.Pageable.unpaged()))
                .hasSize(2);
        assertThat(records.findById(first.recordId()).orElseThrow().getVerificationStatus())
                .isEqualTo(EvidenceVerificationRecord.Status.MATCH);
    }

    private static byte[] image(int color) throws Exception {
        BufferedImage picture = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        picture.setRGB(1, 1, color);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(picture, "png", output);
        return output.toByteArray();
    }
    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static void authenticate(String subject) {
        JwtAuthenticationToken token = new JwtAuthenticationToken(
                Jwt.withTokenValue("synthetic").header("alg", "RS256").subject(subject).build());
        token.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(token);
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
