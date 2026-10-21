package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.in.web.dto.FanReportReceipt;
import com.ticket.defender_core.adapter.in.web.dto.FanReportSubmission;
import com.ticket.defender_core.adapter.out.persistence.EvidenceAssetRepository;
import com.ticket.defender_core.adapter.out.persistence.FanReportRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantEventRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantRepository;
import com.ticket.defender_core.application.service.EvidenceImageValidator;
import com.ticket.defender_core.application.service.EvidenceObjectStorage;
import com.ticket.defender_core.application.service.FanReportApiException;
import com.ticket.defender_core.application.service.FanReportCrypto;
import com.ticket.defender_core.application.service.FanReportIntakeService;
import com.ticket.defender_core.application.service.FanReportPersistence;
import com.ticket.defender_core.application.service.PublicEventLookup;
import com.ticket.defender_core.application.service.ValidatedImage;
import com.ticket.defender_core.domain.AccessRecordStatus;
import com.ticket.defender_core.domain.EvidenceAsset;
import com.ticket.defender_core.domain.FanReport;
import com.ticket.defender_core.domain.Tenant;
import com.ticket.defender_core.domain.TenantEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Exception;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "ticket-defender.fan-report.enabled=true",
        "ticket-defender.fan-report.hmac-key-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
@EnabledIfEnvironmentVariable(named = "TD_ISSUE32_MINIO_ENDPOINT", matches = "http://127\\.0\\.0\\.1:19000")
class FanReportMinioMySqlTest {
    private static final String RECOVERY_SECRET = "A".repeat(43);
    @Autowired private FanReportIntakeService intake;
    @Autowired private FanReportPersistence persistence;
    @Autowired private FanReportRepository reports;
    @Autowired private EvidenceAssetRepository assets;
    @Autowired private TenantRepository tenants;
    @Autowired private TenantEventRepository events;
    @Autowired private PublicEventLookup eventLookup;
    @Autowired private EvidenceImageValidator validator;
    @Autowired private FanReportCrypto crypto;
    @Autowired private EvidenceObjectStorage storage;
    private S3Client admin;
    private String bucket;
    private UUID tenantId;
    private UUID eventId;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> required("TD_ISSUE32_MYSQL_URL"));
        registry.add("spring.datasource.username", () -> required("TD_ISSUE32_MYSQL_USER"));
        registry.add("spring.datasource.password", () -> required("TD_ISSUE32_MYSQL_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("ticket-defender.fan-report.storage.endpoint", () -> required("TD_ISSUE32_MINIO_ENDPOINT"));
        registry.add("ticket-defender.fan-report.storage.bucket", () -> required("TD_ISSUE32_MINIO_BUCKET"));
        registry.add("ticket-defender.fan-report.storage.access-key", () -> required("TD_ISSUE32_MINIO_ACCESS_KEY"));
        registry.add("ticket-defender.fan-report.storage.secret-key", () -> required("TD_ISSUE32_MINIO_SECRET_KEY"));
    }

    @BeforeAll
    void connectAdmin() {
        bucket = required("TD_ISSUE32_MINIO_BUCKET");
        admin = S3Client.builder().endpointOverride(URI.create(required("TD_ISSUE32_MINIO_ENDPOINT")))
                .region(Region.US_EAST_1).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        required("TD_ISSUE32_MINIO_ADMIN_ACCESS_KEY"),
                        required("TD_ISSUE32_MINIO_ADMIN_SECRET_KEY")))).build();
    }

    @AfterAll
    void closeAdmin() { if (admin != null) admin.close(); }

    @BeforeEach
    void seedScope() {
        tenantId = UUID.randomUUID();
        eventId = UUID.randomUUID();
        tenants.save(new Tenant(tenantId, "Synthetic MinIO tenant", AccessRecordStatus.ACTIVE));
        TenantEvent event = new TenantEvent(eventId, tenantId, "synthetic-ref", "Synthetic MinIO event",
                AccessRecordStatus.ACTIVE);
        event.setPublicIntakeEnabled(true);
        events.saveAndFlush(event);
    }

    @AfterEach
    void removeOnlyThisTestsObjects() {
        for (FanReport report : reports.findAll()) {
            if (!tenantId.equals(report.getTenantId())) continue;
            for (EvidenceAsset asset : assets.findByFanReportIdOrderByCreatedAtAsc(report.getFanReportId()))
                admin.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(asset.getObjectKey()).build());
        }
    }

    @Test
    void completedReplayAfterEventClosesKeepsOneRealObjectAndLookupCode() throws Exception {
        String key = UUID.randomUUID().toString();
        byte[] bytes = png();
        FanReportReceipt first = intake.submit(submission(), List.of(file(bytes)), key, RECOVERY_SECRET);
        EvidenceAsset asset = assets.findByFanReportIdOrderByCreatedAtAsc(first.fanReportId()).getFirst();
        assertThat(asset.getStorageStatus()).isEqualTo(EvidenceAsset.StorageStatus.STORED);
        assertThat(admin.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket)
                .key(asset.getObjectKey()).build()).asByteArray()).isEqualTo(bytes);
        TenantEvent event = events.findById(eventId).orElseThrow();
        event.setPublicIntakeEnabled(false);
        events.saveAndFlush(event);

        FanReportReceipt replay = intake.submit(submission(), List.of(file(bytes)), key, RECOVERY_SECRET);
        assertThat(replay.fanReportId()).isEqualTo(first.fanReportId());
        assertThat(replay.lookupCode()).isEqualTo(first.lookupCode());
        assertThat(replay.replayed()).isTrue();
        assertThat(assets.findByFanReportIdOrderByCreatedAtAsc(first.fanReportId())).hasSize(1);
        assertThat(admin.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket)
                .prefix("fan-reports/" + first.fanReportId() + "/").build()).contents()).hasSize(1);

        persistence.cleanStale(first.fanReportId(), LocalDateTime.now(ZoneOffset.UTC).plusHours(1));
        assertThat(reports.findById(first.fanReportId()).orElseThrow().getIntakeStatus())
                .isEqualTo(FanReport.IntakeStatus.RECEIVED);
        admin.headObject(HeadObjectRequest.builder().bucket(bucket).key(asset.getObjectKey()).build());
    }

    @Test
    void partialRealUploadIsReconciledWithoutDeletingAnotherReport() throws Exception {
        byte[] bytes = png();
        FanReportReceipt completed = intake.submit(submission(), List.of(file(bytes)),
                UUID.randomUUID().toString(), RECOVERY_SECRET);
        String completedKey = assets.findByFanReportIdOrderByCreatedAtAsc(completed.fanReportId())
                .getFirst().getObjectKey();
        AtomicInteger writes = new AtomicInteger();
        EvidenceObjectStorage failing = new EvidenceObjectStorage() {
            @Override public void checkReady() { storage.checkReady(); }
            @Override public String put(String key, ValidatedImage image) {
                if (writes.incrementAndGet() == 1) return storage.put(key, image);
                throw new IllegalStateException("Synthetic storage failure after first real upload");
            }
            @Override public void delete(String key, String version) {
                throw new IllegalStateException("Synthetic cleanup outage");
            }
        };
        FanReportIntakeService failingIntake = new FanReportIntakeService(eventLookup, validator, crypto,
                reports, persistence, failing);
        ReflectionTestUtils.setField(failingIntake, "retentionDays", 90);
        String key = UUID.randomUUID().toString();
        assertThatThrownBy(() -> failingIntake.submit(submission(), List.of(file(bytes), file(bytes)),
                key, RECOVERY_SECRET)).isInstanceOf(FanReportApiException.class)
                .hasMessage("INTAKE_STORAGE_UNAVAILABLE");
        FanReport failed = reports.findByIdempotencyKeyDigest(crypto.idempotencyDigest(key)).orElseThrow();
        List<EvidenceAsset> failedAssets = assets.findByFanReportIdOrderByCreatedAtAsc(failed.getFanReportId());
        assertThat(failed.getIntakeStatus()).isEqualTo(FanReport.IntakeStatus.STORAGE_FAILED);
        assertThat(failedAssets).hasSize(2);
        assertThat(failedAssets).extracting(EvidenceAsset::getStorageStatus)
                .containsOnly(EvidenceAsset.StorageStatus.PENDING);
        assertThat(admin.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket)
                .prefix("fan-reports/" + failed.getFanReportId() + "/").build()).contents()).hasSize(1);
        assertThat(reports.findNeedingCleanup(LocalDateTime.now(ZoneOffset.UTC).plusHours(1),
                FanReport.IntakeStatus.PENDING_STORAGE, FanReport.IntakeStatus.STORAGE_FAILED,
                EvidenceAsset.StorageStatus.FAILED, PageRequest.of(0, 50)))
                .extracting(FanReport::getFanReportId).contains(failed.getFanReportId());

        persistence.cleanStale(failed.getFanReportId(), LocalDateTime.now(ZoneOffset.UTC).plusHours(1));
        assertThat(assets.findByFanReportIdOrderByCreatedAtAsc(failed.getFanReportId()))
                .extracting(EvidenceAsset::getStorageStatus).containsOnly(EvidenceAsset.StorageStatus.FAILED);
        assertThat(admin.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket)
                .prefix("fan-reports/" + failed.getFanReportId() + "/").build()).contents()).isEmpty();
        persistence.cleanStale(failed.getFanReportId(), LocalDateTime.now(ZoneOffset.UTC).plusHours(1));
        admin.headObject(HeadObjectRequest.builder().bucket(bucket).key(completedKey).build());
    }

    @Test
    void rejectedDatabaseFinalizationDeletesTheRealObject() throws Exception {
        UUID reportId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        LocalDateTime received = LocalDateTime.now(ZoneOffset.UTC).minusHours(1);
        String digest = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        FanReport report = new FanReport(reportId, "FR-" + UUID.randomUUID(), tenantId, eventId,
                LocalDateTime.parse("2026-11-01T19:30:00"), null, FanReport.ReportType.OTHER,
                "Synthetic finalization failure report", null, null, null, null,
                received, digest, "a".repeat(64), "b".repeat(64));
        byte[] bytes = png();
        String objectKey = "fan-reports/" + reportId + "/" + assetId;
        EvidenceAsset asset = new EvidenceAsset(assetId, reportId, objectKey, "image/png",
                bytes.length, sha256(bytes), received, received.plusDays(90));
        persistence.reserve(report, List.of(asset));
        storage.put(objectKey, new ValidatedImage(bytes, "image/png", bytes.length, sha256(bytes)));
        admin.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey).build());
        assertThatThrownBy(() -> persistence.markReceived(reportId, Map.of()))
                .isInstanceOf(IllegalStateException.class).hasMessage("Evidence count mismatch");
        assertThat(reports.findById(reportId).orElseThrow().getIntakeStatus())
                .isEqualTo(FanReport.IntakeStatus.PENDING_STORAGE);

        persistence.cleanStale(reportId, LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1));
        assertThat(reports.findById(reportId).orElseThrow().getIntakeStatus())
                .isEqualTo(FanReport.IntakeStatus.STORAGE_FAILED);
        assertThat(assets.findById(assetId).orElseThrow().getStorageStatus())
                .isEqualTo(EvidenceAsset.StorageStatus.FAILED);
        assertThatThrownBy(() -> admin.headObject(HeadObjectRequest.builder().bucket(bucket)
                .key(objectKey).build())).isInstanceOfSatisfying(S3Exception.class,
                error -> assertThat(error.statusCode()).isEqualTo(404));
    }

    private FanReportSubmission submission() {
        return new FanReportSubmission(eventId, LocalDateTime.parse("2026-11-01T19:30:00"), null,
                FanReport.ReportType.OTHER, "A synthetic suspected listing description",
                null, null, null, null);
    }

    private static MockMultipartFile file(byte[] bytes) {
        return new MockMultipartFile("evidence", "synthetic.png", "image/png", bytes);
    }

    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(1, 1, 0x00123456);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
