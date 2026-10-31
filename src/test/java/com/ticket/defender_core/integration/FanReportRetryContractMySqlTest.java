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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import javax.sql.DataSource;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * Issue #52 재시도 계약을 격리 MySQL 8과 MinIO에서 검증한다.
 * 확정 저장 실패만 기존 503 코드를 쓰고, 결과를 확정할 수 없는 장애는 같은 키 재시도용 코드로 구분한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "ticket-defender.fan-report.enabled=true",
        "ticket-defender.fan-report.hmac-key-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        // 테이블 잠금 대기를 짧게 끝내 요청 제한 DB 장애를 빠르게 재현한다.
        "spring.datasource.hikari.connection-init-sql=SET SESSION lock_wait_timeout = 2, innodb_lock_wait_timeout = 2"
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "TD_ISSUE32_MINIO_ENDPOINT", matches = "http://127\\.0\\.0\\.1:19000")
class FanReportRetryContractMySqlTest {
    private static final String RECOVERY_SECRET = "A".repeat(43);
    private static final String WRONG_SECRET = "B".repeat(42) + "A";
    private static final AtomicInteger ADDRESS = new AtomicInteger(1);
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private DataSource dataSource;
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
    private final String runPrefix = "198.18." + (int) (Math.random() * 200) + ".";

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
        tenants.save(new Tenant(tenantId, "Synthetic retry tenant", AccessRecordStatus.ACTIVE));
        TenantEvent event = new TenantEvent(eventId, tenantId, "synthetic-retry-ref", "Synthetic retry event",
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
    void quotaTableLockIsOutcomeUnknownAndSameKeyRecoversReceivedReport() throws Exception {
        String key = UUID.randomUUID().toString();
        byte[] first = png(0x00112233);
        byte[] second = png(0x00445566);
        MockHttpServletResponse created = submit(key, RECOVERY_SECRET, description(), first, second);
        assertThat(created.getStatus()).isEqualTo(201);
        JsonNode receipt = json.readTree(created.getContentAsString());
        UUID reportId = UUID.fromString(receipt.path("fanReportId").asText());

        try (Connection locker = dataSource.getConnection(); Statement statement = locker.createStatement()) {
            statement.execute("LOCK TABLES public_request_quota WRITE");
            try {
                MockHttpServletResponse locked = submit(key, RECOVERY_SECRET, description(), first, second);
                assertThat(locked.getStatus()).isEqualTo(503);
                assertThat(code(locked)).isEqualTo("INTAKE_OUTCOME_UNKNOWN");
                assertThat(locked.getHeader("Retry-After")).isEqualTo("5");
                assertThat(locked.getContentAsString()).doesNotContain(receipt.path("receiptNumber").asText());
            } finally {
                statement.execute("UNLOCK TABLES");
            }
        }

        MockHttpServletResponse recovered = submit(key, RECOVERY_SECRET, description(), first, second);
        assertThat(recovered.getStatus()).isEqualTo(200);
        JsonNode replay = json.readTree(recovered.getContentAsString());
        assertThat(replay.path("fanReportId").asText()).isEqualTo(receipt.path("fanReportId").asText());
        assertThat(replay.path("receiptNumber").asText()).isEqualTo(receipt.path("receiptNumber").asText());
        assertThat(replay.path("lookupCode").asText()).isEqualTo(receipt.path("lookupCode").asText());
        assertThat(replay.path("receivedAt").asText()).isEqualTo(receipt.path("receivedAt").asText());
        assertThat(replay.path("intakeStatus").asText()).isEqualTo("RECEIVED");

        // 잘못된 복구 비밀은 존재 여부를 숨기고, 본문이나 이미지 순서가 바뀌면 충돌로 거부한다.
        assertThat(submit(key, WRONG_SECRET, description(), first, second).getStatus()).isEqualTo(404);
        MockHttpServletResponse changedBody = submit(key, RECOVERY_SECRET,
                "A changed synthetic suspected listing description", first, second);
        assertThat(changedBody.getStatus()).isEqualTo(409);
        assertThat(code(changedBody)).isEqualTo("IDEMPOTENCY_CONFLICT");
        MockHttpServletResponse swapped = submit(key, RECOVERY_SECRET, description(), second, first);
        assertThat(swapped.getStatus()).isEqualTo(409);
        assertThat(code(swapped)).isEqualTo("IDEMPOTENCY_CONFLICT");

        assertSingleReport(key, reportId, 2);
    }

    @Test
    void persistedStorageFailureRequiresNewKeyWithoutCreatingDuplicates() throws Exception {
        byte[] bytes = png(0x00778899);
        String key = UUID.randomUUID().toString();
        EvidenceObjectStorage failing = new EvidenceObjectStorage() {
            @Override public void checkReady() { storage.checkReady(); }
            @Override public String put(String objectKey, ValidatedImage image) {
                throw new IllegalStateException("Synthetic PutObject denial");
            }
            @Override public void delete(String objectKey, String version) { storage.delete(objectKey, version); }
        };
        assertThatThrownBy(() -> intakeWith(persistence, failing).submit(submission(description()),
                List.of(file(bytes)), key, RECOVERY_SECRET))
                .isInstanceOf(FanReportApiException.class).hasMessage("INTAKE_STORAGE_UNAVAILABLE");
        FanReport failed = reports.findByIdempotencyKeyDigest(crypto.idempotencyDigest(key)).orElseThrow();
        assertThat(failed.getIntakeStatus()).isEqualTo(FanReport.IntakeStatus.STORAGE_FAILED);
        assertThat(assets.findByFanReportIdOrderByCreatedAtAsc(failed.getFanReportId()))
                .extracting(EvidenceAsset::getStorageStatus).containsOnly(EvidenceAsset.StorageStatus.FAILED);
        assertThat(objectCount(failed.getFanReportId())).isZero();

        for (int attempt = 0; attempt < 2; attempt++) {
            MockHttpServletResponse replay = submit(key, RECOVERY_SECRET, description(), bytes);
            assertThat(replay.getStatus()).isEqualTo(503);
            assertThat(code(replay)).isEqualTo("INTAKE_STORAGE_UNAVAILABLE");
            assertThat(replay.getHeader("Retry-After")).isNull();
        }
        assertThat(submit(key, WRONG_SECRET, description(), bytes).getStatus()).isEqualTo(404);
        assertSingleReport(key, failed.getFanReportId(), 1);
        assertThat(objectCount(failed.getFanReportId())).isZero();

        // 사용자가 직접 새 키로 다시 제출해야만 새 접수가 생성된다.
        String newKey = UUID.randomUUID().toString();
        MockHttpServletResponse created = submit(newKey, RECOVERY_SECRET, description(), bytes);
        assertThat(created.getStatus()).isEqualTo(201);
        UUID newReport = UUID.fromString(json.readTree(created.getContentAsString()).path("fanReportId").asText());
        assertThat(newReport).isNotEqualTo(failed.getFanReportId());
        assertSingleReport(newKey, newReport, 1);
        assertThat(objectCount(newReport)).isEqualTo(1);
    }

    @Test
    void lostFinalizationAcknowledgementIsResolvedByLockedReRead() throws Exception {
        byte[] bytes = png(0x00abcdef);
        String key = UUID.randomUUID().toString();
        FanReportPersistence lostAck = new FanReportPersistence(reports, assets, storage, events, tenants) {
            @Override public void reserve(FanReport report, List<EvidenceAsset> evidence) {
                persistence.reserve(report, evidence);
            }
            @Override public void markReceived(UUID reportId, Map<UUID, String> versions) {
                persistence.markReceived(reportId, versions);
                throw new DataAccessResourceFailureException("Synthetic lost commit acknowledgement");
            }
            @Override public void cleanStale(UUID reportId, LocalDateTime before) {
                persistence.cleanStale(reportId, before);
            }
            @Override public void markStorageFailed(UUID reportId) { persistence.markStorageFailed(reportId); }
            @Override public void markCleaned(UUID reportId) { persistence.markCleaned(reportId); }
        };
        FanReportReceipt receipt = intakeWith(lostAck, storage).submit(submission(description()),
                List.of(file(bytes)), key, RECOVERY_SECRET);
        assertThat(receipt.intakeStatus()).isEqualTo(FanReport.IntakeStatus.RECEIVED);
        assertThat(objectCount(receipt.fanReportId())).isEqualTo(1);

        MockHttpServletResponse replay = submit(key, RECOVERY_SECRET, description(), bytes);
        assertThat(replay.getStatus()).isEqualTo(200);
        JsonNode recovered = json.readTree(replay.getContentAsString());
        assertThat(recovered.path("receiptNumber").asText()).isEqualTo(receipt.receiptNumber());
        assertThat(recovered.path("lookupCode").asText()).isEqualTo(receipt.lookupCode());
        assertSingleReport(key, receipt.fanReportId(), 1);
        assertThat(objectCount(receipt.fanReportId())).isEqualTo(1);
    }

    @Test
    void unverifiableFinalizationIsOutcomeUnknownUntilReconciliationConfirmsFailure() throws Exception {
        byte[] bytes = png(0x00fedcba);
        String key = UUID.randomUUID().toString();
        FanReportPersistence unreachable = new FanReportPersistence(reports, assets, storage, events, tenants) {
            @Override public void reserve(FanReport report, List<EvidenceAsset> evidence) {
                persistence.reserve(report, evidence);
            }
            @Override public void markReceived(UUID reportId, Map<UUID, String> versions) {
                throw new DataAccessResourceFailureException("Synthetic connection loss before commit");
            }
            @Override public void cleanStale(UUID reportId, LocalDateTime before) {
                throw new DataAccessResourceFailureException("Synthetic connection loss during reconciliation");
            }
            @Override public void markStorageFailed(UUID reportId) { persistence.markStorageFailed(reportId); }
            @Override public void markCleaned(UUID reportId) { persistence.markCleaned(reportId); }
        };
        FanReportIntakeService intake = new FanReportIntakeService(eventLookup, validator, crypto,
                unreachableReads(), unreachable, storage);
        ReflectionTestUtils.setField(intake, "retentionDays", 90);
        assertThatThrownBy(() -> intake.submit(submission(description()), List.of(file(bytes)), key, RECOVERY_SECRET))
                .isInstanceOf(FanReportApiException.class).hasMessage("INTAKE_OUTCOME_UNKNOWN");
        FanReport pending = reports.findByIdempotencyKeyDigest(crypto.idempotencyDigest(key)).orElseThrow();
        assertThat(pending.getIntakeStatus()).isEqualTo(FanReport.IntakeStatus.PENDING_STORAGE);
        // 결과가 불확실한 동안 업로드된 원본을 지우지 않는다.
        assertThat(objectCount(pending.getFanReportId())).isEqualTo(1);

        MockHttpServletResponse inProgress = submit(key, RECOVERY_SECRET, description(), bytes);
        assertThat(inProgress.getStatus()).isEqualTo(409);
        assertThat(code(inProgress)).isEqualTo("INTAKE_IN_PROGRESS");

        persistence.cleanStale(pending.getFanReportId(), LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1));
        MockHttpServletResponse confirmed = submit(key, RECOVERY_SECRET, description(), bytes);
        assertThat(confirmed.getStatus()).isEqualTo(503);
        assertThat(code(confirmed)).isEqualTo("INTAKE_STORAGE_UNAVAILABLE");
        assertThat(objectCount(pending.getFanReportId())).isZero();
        assertSingleReport(key, pending.getFanReportId(), 1);
    }

    @Test
    void normalRateLimitStillReturns429WithRetryAfter() throws Exception {
        String address = nextAddress();
        byte[] bytes = png(0x00010203);
        for (int i = 0; i < 2; i++)
            assertThat(submitFrom(address, UUID.randomUUID().toString(), RECOVERY_SECRET, description(), bytes)
                    .getStatus()).isEqualTo(201);
        MockHttpServletResponse limited = submitFrom(address, UUID.randomUUID().toString(), RECOVERY_SECRET,
                description(), bytes);
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(code(limited)).isEqualTo("RATE_LIMITED");
        assertThat(limited.getHeader("Retry-After")).isEqualTo("60");
    }

    /** 실제 저장소 대신 다시 읽기도 실패하는 조회를 주입해 커밋 결과를 확인할 수 없는 상황을 만든다. */
    private FanReportRepository unreachableReads() {
        return (FanReportRepository) java.lang.reflect.Proxy.newProxyInstance(
                FanReportRepository.class.getClassLoader(), new Class<?>[]{FanReportRepository.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("findById"))
                        throw new DataAccessResourceFailureException("Synthetic re-read failure");
                    try { return method.invoke(reports, args); }
                    catch (java.lang.reflect.InvocationTargetException error) { throw error.getCause(); }
                });
    }

    private FanReportIntakeService intakeWith(FanReportPersistence usedPersistence, EvidenceObjectStorage usedStorage) {
        FanReportIntakeService intake = new FanReportIntakeService(eventLookup, validator, crypto,
                reports, usedPersistence, usedStorage);
        ReflectionTestUtils.setField(intake, "retentionDays", 90);
        return intake;
    }

    private void assertSingleReport(String key, UUID reportId, int evidenceCount) {
        assertThat(reports.findAll()).filteredOn(report -> tenantId.equals(report.getTenantId())
                && report.getIdempotencyKeyDigest().equals(crypto.idempotencyDigest(key)))
                .extracting(FanReport::getFanReportId).containsExactly(reportId);
        assertThat(assets.findByFanReportIdOrderByCreatedAtAsc(reportId)).hasSize(evidenceCount);
    }

    private int objectCount(UUID reportId) {
        return admin.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket)
                .prefix("fan-reports/" + reportId + "/").build()).contents().size();
    }

    private MockHttpServletResponse submit(String key, String secret, String description, byte[]... images)
            throws Exception {
        return submitFrom(nextAddress(), key, secret, description, images);
    }

    private MockHttpServletResponse submitFrom(String address, String key, String secret, String description,
                                               byte[]... images) throws Exception {
        var request = multipart("/api/v1/fan-reports").file(reportPart(description));
        for (byte[] image : images) request.file(file(image));
        return mvc.perform(request.header("Idempotency-Key", key).header("Report-Recovery-Secret", secret)
                .with(servlet -> { servlet.setRemoteAddr(address); return servlet; })).andReturn().getResponse();
    }

    private String nextAddress() { return runPrefix + ADDRESS.getAndIncrement(); }

    private String code(MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsString()).path("code").asText();
    }

    private static String description() { return "A synthetic suspected listing description"; }

    private MockMultipartFile reportPart(String description) {
        String body = "{\"eventId\":\"" + eventId + "\",\"performanceAt\":\"2026-11-01T19:30:00\","
                + "\"reportType\":\"OTHER\",\"description\":\"" + description + "\"}";
        return new MockMultipartFile("report", "", MediaType.APPLICATION_JSON_VALUE,
                body.getBytes(StandardCharsets.UTF_8));
    }

    private FanReportSubmission submission(String description) {
        return new FanReportSubmission(eventId, LocalDateTime.parse("2026-11-01T19:30:00"), null,
                FanReport.ReportType.OTHER, description, null, null, null, null);
    }

    private static MockMultipartFile file(byte[] bytes) {
        return new MockMultipartFile("evidence", "synthetic.png", "image/png", bytes);
    }

    private static byte[] png(int rgb) throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(1, 1, rgb);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
