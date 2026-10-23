package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.in.web.dto.CaseDto;
import com.ticket.defender_core.adapter.in.web.dto.CasePreparationDto;
import com.ticket.defender_core.adapter.out.persistence.*;
import com.ticket.defender_core.application.service.CaseApiException;
import com.ticket.defender_core.application.service.CasePreparationService;
import com.ticket.defender_core.application.service.CaseService;
import com.ticket.defender_core.application.service.EvidenceObjectStorage;
import com.ticket.defender_core.application.service.ValidatedImage;
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
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

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
class CaseW05MinioMySqlTest {
    @Autowired private CaseService casesService;
    @Autowired private CasePreparationService preparation;
    @Autowired private EvidenceObjectStorage storage;
    @Autowired private TenantRepository tenants;
    @Autowired private TenantEventRepository events;
    @Autowired private AdminMembershipRepository memberships;
    @Autowired private MembershipRoleRepository roles;
    @Autowired private MembershipEventGrantRepository grants;
    @Autowired private FanReportRepository reports;
    @Autowired private EvidenceAssetRepository assets;
    @Autowired private EvidenceSnapshotRepository snapshots;

    private final List<String> createdKeys = new ArrayList<>();
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
    void cleanupObjects() {
        for (String key : createdKeys)
            admin.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        createdKeys.clear();
        SecurityContextHolder.clearContext();
    }

    @AfterAll
    void close() { if (admin != null) admin.close(); }

    @Test
    void realPrivateHeadSealsMetadataOnlyAndOriginalBytesMatchStoredHash() throws Exception {
        Fixture fixture = fixture();
        CaseDto.View reviewing = reviewingCase(fixture);
        byte[] original = fixture.bytes();
        byte[] stored = admin.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket)
                .key(fixture.key()).build()).asByteArray();
        assertThat(stored).isEqualTo(original);
        assertThat(sha256(stored)).isEqualTo(fixture.assetSha256());
        var snapshot = preparation.capture(reviewing.caseId(),
                new CasePreparationDto.Capture(reviewing.caseVersion(), "Capture private evidence"),
                UUID.randomUUID().toString());
        assertThat(snapshot.sourceIntegrity()).isEqualTo("METADATA_ONLY");
        assertThat(snapshot.approvalEligible()).isFalse();
        assertThat(snapshot.manifestJson()).contains(fixture.assetSha256()).doesNotContain(fixture.key());
        assertThat(snapshots.findByCaseIdAndEvidenceRevision(reviewing.caseId(), 1)).isPresent();
    }

    @Test
    void missingRealObjectBlocksCaptureWithoutACommittedSnapshot() throws Exception {
        Fixture fixture = fixture();
        CaseDto.View reviewing = reviewingCase(fixture);
        admin.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(fixture.key()).build());
        assertThatThrownBy(() -> preparation.capture(reviewing.caseId(),
                new CasePreparationDto.Capture(reviewing.caseVersion(), "Capture private evidence"),
                UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("SOURCE_OBJECT_MISSING"));
        assertThat(snapshots.findByCaseIdAndEvidenceRevision(reviewing.caseId(), 1)).isEmpty();
    }

    private Fixture fixture() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        String subject = "w05-minio-reviewer-" + membershipId;
        tenants.saveAndFlush(new Tenant(tenantId, "Synthetic W05 tenant", AccessRecordStatus.ACTIVE));
        events.saveAndFlush(new TenantEvent(eventId, tenantId, "synthetic-show", "Synthetic show",
                AccessRecordStatus.ACTIVE));
        memberships.saveAndFlush(new AdminMembershipEntity(membershipId, tenantId, subject,
                AccessRecordStatus.ACTIVE));
        roles.saveAndFlush(new MembershipRole(UUID.randomUUID(), membershipId, AdminRole.REVIEWER));
        grants.saveAndFlush(new MembershipEventGrant(UUID.randomUUID(), membershipId, tenantId, eventId));
        UUID reportId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        FanReport report = new FanReport(reportId, "FR-" + reportId, tenantId, eventId,
                now.plusDays(1), null, FanReport.ReportType.OTHER,
                "Synthetic private evidence", null, null, null, null, now,
                UUID.randomUUID().toString(), "d".repeat(64), "e".repeat(64));
        report.markReceived();
        reports.saveAndFlush(report);
        byte[] bytes = png();
        UUID evidenceId = UUID.randomUUID();
        String key = "fan-reports/" + reportId + "/" + evidenceId;
        String digest = sha256(bytes);
        String marker = storage.put(key, new ValidatedImage(bytes, "image/png", bytes.length, digest));
        createdKeys.add(key);
        EvidenceAsset asset = new EvidenceAsset(evidenceId, reportId, key, "image/png",
                bytes.length, digest, now, report.getRetentionUntil());
        asset.markStored(marker);
        assets.saveAndFlush(asset);
        authenticate(subject);
        return new Fixture(eventId, membershipId, reportId, evidenceId, key, digest, bytes);
    }

    private CaseDto.View reviewingCase(Fixture fixture) {
        CaseDto.View opened = casesService.create(new CaseDto.Create(fixture.eventId(), Case.Type.FAN_REPORT,
                List.of(), List.of(fixture.reportId()), List.of(fixture.evidenceId()),
                "Investigate synthetic private evidence", null, null), UUID.randomUUID().toString());
        CaseDto.View assigned = casesService.assign(opened.caseId(),
                new CaseDto.Assign(fixture.membershipId(), opened.caseVersion(), "Assign reviewer"),
                UUID.randomUUID().toString());
        CaseDto.View triaged = casesService.transition(opened.caseId(),
                new CaseDto.Transition(Case.Status.TRIAGED, assigned.caseVersion(), "Triage case",
                        "Synthetic classification", null, null, null, null, null), UUID.randomUUID().toString());
        return casesService.transition(opened.caseId(),
                new CaseDto.Transition(Case.Status.IN_REVIEW, triaged.caseVersion(), "Begin review",
                        null, null, null, null, null, null), UUID.randomUUID().toString());
    }

    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void authenticate(String subject) {
        Jwt jwt = Jwt.withTokenValue("synthetic").header("alg", "RS256").subject(subject).build();
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt);
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private record Fixture(UUID eventId, UUID membershipId, UUID reportId, UUID evidenceId,
                           String key, String assetSha256, byte[] bytes) { }
}
