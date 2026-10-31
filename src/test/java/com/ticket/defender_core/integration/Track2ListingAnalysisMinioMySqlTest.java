package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.out.persistence.*;
import com.ticket.defender_core.application.service.CaseApiException;
import com.ticket.defender_core.application.service.EvidenceObjectStorage;
import com.ticket.defender_core.application.service.ListingContextAnalysisService;
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
import org.springframework.data.domain.Pageable;
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
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "ticket-defender.fan-report.hmac-key-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "ticket-defender.track2-listing-analysis.enabled=true",
        "ticket-defender.track2-listing-analysis.internal-key=" + FakeListingContextServer.INTERNAL_KEY
})
@EnabledIfEnvironmentVariable(named = "TD_ISSUE32_MINIO_ENDPOINT", matches = "http://127\\.0\\.0\\.1:19000")
class Track2ListingAnalysisMinioMySqlTest {
    private static final FakeListingContextServer PYTHON = start();

    @Autowired private ListingContextAnalysisService service;
    @Autowired private EvidenceObjectStorage storage;
    @Autowired private TenantRepository tenants;
    @Autowired private TenantEventRepository events;
    @Autowired private AdminMembershipRepository memberships;
    @Autowired private MembershipRoleRepository roles;
    @Autowired private MembershipEventGrantRepository grants;
    @Autowired private FanReportRepository reports;
    @Autowired private EvidenceAssetRepository assets;
    @Autowired private ListingContextAnalysisRepository analyses;
    @Autowired private ListingContextObservationRepository observations;

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
        registry.add("ticket-defender.track2-listing-analysis.base-url", PYTHON::baseUrl);
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
    void close() {
        if (admin != null) admin.close();
        PYTHON.close();
    }

    @Test
    void realPrivateOriginalIsVerifiedBeforeAnalysisAndReplacementOrLossIsRejected() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        String subject = "listing-minio-analyst-" + member;
        tenants.saveAndFlush(new Tenant(tenant, "Synthetic listing tenant", AccessRecordStatus.ACTIVE));
        events.saveAndFlush(new TenantEvent(event, tenant, "synthetic-" + event, "Synthetic show",
                AccessRecordStatus.ACTIVE));
        memberships.saveAndFlush(new AdminMembershipEntity(member, tenant, subject, AccessRecordStatus.ACTIVE));
        roles.saveAndFlush(new MembershipRole(UUID.randomUUID(), member, AdminRole.ANALYST));
        grants.saveAndFlush(new MembershipEventGrant(UUID.randomUUID(), member, tenant, event));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        UUID reportId = UUID.randomUUID();
        FanReport report = new FanReport(reportId, "FR-" + reportId, tenant, event, now.plusDays(1), null,
                FanReport.ReportType.PREMIUM_RESALE, "Synthetic listing image", null, null, null, null, now,
                UUID.randomUUID().toString(), "d".repeat(64), "e".repeat(64));
        report.markReceived();
        reports.saveAndFlush(report);
        byte[] original = image(0x00335577);
        UUID evidenceId = store(reportId, original, report.getRetentionUntil(), true);
        UUID missingId = store(reportId, original, report.getRetentionUntil(), false);
        authenticate(subject);
        PYTHON.received.clear();

        var first = service.analyze(reportId, evidenceId, UUID.randomUUID().toString());
        assertThat(first.analysisStatus()).isEqualTo(ListingContextAnalysis.AnalysisStatus.COMPLETED);
        assertThat(first.input().verifiedSha256()).isEqualTo(sha256(original));
        assertThat(PYTHON.received).hasSize(1);
        assertThat(Base64.getDecoder().decode((String) PYTHON.received.getFirst().body().get("imageBase64")))
                .isEqualTo(original);
        assertThat(observations.findByAnalysisIdIn(List.of(first.analysisId()))).hasSize(9);

        String key = assets.findById(evidenceId).orElseThrow().getObjectKey();
        admin.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        admin.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType("image/png").build(),
                RequestBody.fromBytes(image(0x00aa5533)));
        assertThatThrownBy(() -> service.analyze(reportId, evidenceId, UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("OBJECT_CHANGED"));
        assertThatThrownBy(() -> service.analyze(reportId, missingId, UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("OBJECT_MISSING"));
        assertThat(PYTHON.received).hasSize(1);
        assertThat(analyses.findByFanReportIdAndEvidenceId(reportId, evidenceId, Pageable.unpaged()))
                .extracting(ListingContextAnalysis::getExecutionStatus)
                .containsExactlyInAnyOrder(ListingContextAnalysis.Execution.SUCCEEDED,
                        ListingContextAnalysis.Execution.INPUT_REJECTED);
        assertThat(analyses.findById(first.analysisId()).orElseThrow().getAnalysisStatus())
                .isEqualTo(ListingContextAnalysis.AnalysisStatus.COMPLETED);
    }

    private UUID store(UUID reportId, byte[] bytes, LocalDateTime retentionUntil, boolean upload) throws Exception {
        UUID evidenceId = UUID.randomUUID();
        String key = "fan-reports/" + reportId + "/" + evidenceId;
        String digest = sha256(bytes);
        String marker = "missing-marker";
        if (upload) {
            marker = storage.put(key, new ValidatedImage(bytes, "image/png", bytes.length, digest));
            keys.add(key);
        }
        EvidenceAsset asset = new EvidenceAsset(evidenceId, reportId, key, "image/png", bytes.length, digest,
                LocalDateTime.now(ZoneOffset.UTC), retentionUntil);
        asset.markStored(marker);
        assets.saveAndFlush(asset);
        return evidenceId;
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

    private static FakeListingContextServer start() {
        try {
            return new FakeListingContextServer();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
