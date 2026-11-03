package com.ticket.defender_core.integration;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.ticket.defender_core.adapter.out.persistence.AdminMembershipRepository;
import com.ticket.defender_core.adapter.out.persistence.EvidenceAccessAuditEventRepository;
import com.ticket.defender_core.adapter.out.persistence.EvidenceAssetRepository;
import com.ticket.defender_core.adapter.out.persistence.FanReportRepository;
import com.ticket.defender_core.adapter.out.persistence.MembershipEventGrantRepository;
import com.ticket.defender_core.adapter.out.persistence.MembershipRoleRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantEventRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantRepository;
import com.ticket.defender_core.application.service.EvidenceObjectStorage;
import com.ticket.defender_core.application.service.ValidatedImage;
import com.ticket.defender_core.domain.AccessRecordStatus;
import com.ticket.defender_core.domain.AdminMembershipEntity;
import com.ticket.defender_core.domain.EvidenceAccessAuditEvent;
import com.ticket.defender_core.domain.EvidenceAsset;
import com.ticket.defender_core.domain.FanReport;
import com.ticket.defender_core.domain.MembershipEventGrant;
import com.ticket.defender_core.domain.MembershipRole;
import com.ticket.defender_core.domain.Tenant;
import com.ticket.defender_core.domain.TenantEvent;
import com.ticket.defender_core.security.AdminRole;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * 실제 Tomcat 포트로 관리자 원본 열람 API를 호출하고, 격리 MinIO의 비공개 원본과 격리 MySQL 8의 감사 기록을 확인합니다.
 * 관리자 JWT는 테스트에서 만든 RSA 키로 서명하고 서명, 만료, 발급자만 검사합니다. 실제 Keycloak은 사용하지 않습니다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "ticket-defender.fan-report.hmac-key-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        // 합성 테스트 값입니다. 운영 정책으로 확정한 동시 읽기 수가 아닙니다.
        "ticket-defender.evidence-content.max-concurrent-reads=2"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TD_ISSUE32_MINIO_ENDPOINT", matches = "http://127\\.0\\.0\\.1:19000")
class AdminEvidenceContentMinioMySqlTest {
    private static final String ADMIN_ISSUER = "https://issuer.example.test/admin";
    private static KeyPair keyPair;

    @Value("${local.server.port}") private int port;
    @Autowired private EvidenceObjectStorage storage;
    @Autowired private TenantRepository tenants;
    @Autowired private TenantEventRepository events;
    @Autowired private AdminMembershipRepository memberships;
    @Autowired private MembershipRoleRepository roles;
    @Autowired private MembershipEventGrantRepository grants;
    @Autowired private FanReportRepository reports;
    @Autowired private EvidenceAssetRepository assets;
    @Autowired private EvidenceAccessAuditEventRepository audits;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean(name = "adminJwtDecoder") private JwtDecoder adminDecoder;

    private final List<String> keys = new ArrayList<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private S3Client admin;
    private String bucket;
    private UUID tenant;
    private UUID event;
    private String run;

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
    void connect() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        bucket = required("TD_ISSUE32_MINIO_BUCKET");
        admin = S3Client.builder().endpointOverride(URI.create(required("TD_ISSUE32_MINIO_ENDPOINT")))
                .region(Region.US_EAST_1).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        required("TD_ISSUE32_MINIO_ADMIN_ACCESS_KEY"),
                        required("TD_ISSUE32_MINIO_ADMIN_SECRET_KEY")))).build();
    }

    @BeforeEach
    void seed() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keyPair.getPublic()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ADMIN_ISSUER));
        given(adminDecoder.decode(anyString())).willAnswer(call -> decoder.decode(call.getArgument(0)));
        run = UUID.randomUUID().toString().substring(0, 8);
        tenant = UUID.randomUUID();
        event = UUID.randomUUID();
        tenants.saveAndFlush(new Tenant(tenant, "원본 열람 합성 고객사", AccessRecordStatus.ACTIVE));
        events.saveAndFlush(new TenantEvent(event, tenant, "content-" + run, "합성 공연", AccessRecordStatus.ACTIVE));
        member(tenant, event, "analyst", AdminRole.ANALYST);
        member(tenant, event, "reviewer", AdminRole.REVIEWER);
        UUID otherTenant = UUID.randomUUID();
        UUID otherEvent = UUID.randomUUID();
        tenants.saveAndFlush(new Tenant(otherTenant, "다른 합성 고객사", AccessRecordStatus.ACTIVE));
        events.saveAndFlush(new TenantEvent(otherEvent, otherTenant, "content-" + run, "다른 공연",
                AccessRecordStatus.ACTIVE));
        member(otherTenant, otherEvent, "other-reviewer", AdminRole.REVIEWER);
    }

    @AfterEach
    void cleanup() {
        for (String key : keys) admin.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        keys.clear();
    }

    @AfterAll
    void close() {
        if (admin != null) admin.close();
    }

    @Test
    void realHttpServesOnlyVerifiedPrivateOriginalsAndAuditsEveryAuthorizedAttempt() throws Exception {
        UUID report = report();
        byte[] png = image("png", 0x00336699);
        byte[] jpeg = image("jpg", 0x00cc3300);
        UUID pngId = store(report, png, "image/png", true, null);
        UUID jpegId = store(report, jpeg, "image/jpeg", true, null);
        UUID tamperedId = store(report, png, "image/png", true, null);
        UUID hashId = store(report, png, "image/png", true, "0".repeat(64));
        UUID missingId = store(report, png, "image/png", false, null);
        String pngKey = assets.findById(pngId).orElseThrow().getObjectKey();

        HttpResponse<byte[]> first = get(report, pngId, "analyst");
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.body()).isEqualTo(png);
        assertThat(sha256(first.body())).isEqualTo(sha256(png));
        assertThat(first.headers().firstValue("Content-Type")).hasValue("image/png");
        assertThat(first.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(first.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(first.headers().firstValue("Content-Disposition"))
                .hasValue("inline; filename=\"evidence-" + pngId + ".png\"");
        assertThat(first.headers().map().toString()).doesNotContain(pngKey, bucket, "19000", "fan-reports/");
        HttpResponse<byte[]> second = get(report, jpegId, "reviewer");
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(second.body()).isEqualTo(jpeg);
        assertThat(second.headers().firstValue("Content-Type")).hasValue("image/jpeg");

        // 같은 키에 다른 내용을 덮어쓰면 ETag가 바뀌므로 원본 변경으로 거절합니다.
        String tamperedKey = assets.findById(tamperedId).orElseThrow().getObjectKey();
        admin.putObject(PutObjectRequest.builder().bucket(bucket).key(tamperedKey).contentType("image/png").build(),
                RequestBody.fromBytes(image("png", 0x00ffffff)));
        expectProblem(get(report, tamperedId, "analyst"), 422, "OBJECT_CHANGED");
        // 저장소 객체는 그대로지만 DB에 기록된 SHA-256과 실제 바이트의 SHA-256이 다른 경우입니다.
        expectProblem(get(report, hashId, "analyst"), 422, "HASH_MISMATCH");
        expectProblem(get(report, missingId, "analyst"), 422, "OBJECT_MISSING");
        expectProblem(get(report, pngId, "other-reviewer"), 404, "RESOURCE_NOT_FOUND");
        HttpResponse<byte[]> anonymous = http.send(request(report, pngId).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(anonymous.headers().firstValue("Content-Type").orElse("")).doesNotStartWith("image/");
        assertThat(anonymous.body()).isNotEqualTo(png);

        List<EvidenceAccessAuditEvent> stored = auditsOf(report);
        assertThat(stored).extracting(EvidenceAccessAuditEvent::getDetailCode).containsExactlyInAnyOrder(
                "VERIFIED_FOR_RESPONSE", "VERIFIED_FOR_RESPONSE", "OBJECT_CHANGED", "HASH_MISMATCH", "OBJECT_MISSING");
        assertThat(stored).allSatisfy(row -> {
            assertThat(row.getTenantId()).isEqualTo(tenant);
            assertThat(row.getEventId()).isEqualTo(event);
        });
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT * FROM evidence_access_audit_events WHERE fan_report_id=?", report.toString()))
            assertThat(row.toString()).doesNotContain("fan-reports/", bucket, "Bearer");
    }

    @Test
    void concurrentRequestsNeverCreateMoreSuccessAuditsThanDeliveredImages() throws Exception {
        UUID report = report();
        byte[] png = image("png", 0x00123456);
        UUID evidence = store(report, png, "image/png", true, null);
        String token = bearer("analyst");
        int requests = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(requests);
        List<HttpResponse<byte[]>> responses = new ArrayList<>();
        try {
            List<Future<HttpResponse<byte[]>>> futures = new ArrayList<>();
            for (int index = 0; index < requests; index++)
                futures.add(executor.submit(() -> {
                    start.await();
                    return http.send(request(report, evidence).header("Authorization", token).build(),
                            HttpResponse.BodyHandlers.ofByteArray());
                }));
            start.countDown();
            for (Future<HttpResponse<byte[]>> future : futures) responses.add(future.get(60, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        long delivered = responses.stream().filter(response -> response.statusCode() == 200).count();
        assertThat(delivered).isPositive();
        assertThat(responses).allSatisfy(response -> {
            if (response.statusCode() == 200) assertThat(response.body()).isEqualTo(png);
            else expectProblem(response, 503, "EVIDENCE_CONTENT_BUSY");
        });
        List<EvidenceAccessAuditEvent> stored = auditsOf(report);
        assertThat(stored).hasSize(requests);
        assertThat(stored.stream().filter(row -> row.getOutcome() == EvidenceAccessAuditEvent.Outcome.SUCCEEDED))
                .hasSize((int) delivered);
        assertThat(stored.stream().filter(row -> row.getOutcome() == EvidenceAccessAuditEvent.Outcome.FAILED))
                .allSatisfy(row -> assertThat(row.getDetailCode()).isEqualTo("EVIDENCE_CONTENT_BUSY"));
    }

    private void expectProblem(HttpResponse<byte[]> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).doesNotStartWith("image/");
        assertThat(response.headers().firstValue("Content-Disposition")).isEmpty();
        // 문제 응답의 instance에는 요청한 API 경로가 들어가므로, API 경로가 아닌 실제 객체 키와 버킷이 없는지 확인합니다.
        String[] path = response.request().uri().getPath().split("/");
        String objectKey = "fan-reports/" + path[5] + "/" + path[7];
        assertThat(new String(response.body())).contains("\"code\":\"" + code + "\"")
                .doesNotContain(objectKey, bucket, "19000", "Exception");
    }

    private HttpResponse<byte[]> get(UUID report, UUID evidence, String subject) throws Exception {
        return http.send(request(report, evidence).header("Authorization", bearer(subject)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpRequest.Builder request(UUID report, UUID evidence) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/admin/fan-reports/"
                + report + "/evidence/" + evidence + "/content")).timeout(Duration.ofSeconds(30)).GET();
    }

    private List<EvidenceAccessAuditEvent> auditsOf(UUID report) {
        return audits.findAll().stream().filter(row -> report.equals(row.getFanReportId())).toList();
    }

    private void member(UUID tenantId, UUID eventId, String subject, AdminRole role) {
        UUID membership = UUID.randomUUID();
        memberships.saveAndFlush(new AdminMembershipEntity(membership, tenantId, subject + "-" + run,
                AccessRecordStatus.ACTIVE));
        roles.saveAndFlush(new MembershipRole(UUID.randomUUID(), membership, role));
        grants.saveAndFlush(new MembershipEventGrant(UUID.randomUUID(), membership, tenantId, eventId));
    }

    private UUID report() {
        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        FanReport report = new FanReport(id, "FR-" + id, tenant, event, now.plusDays(10), null,
                FanReport.ReportType.PREMIUM_RESALE, "합성 원본 열람 제보", null, null, null,
                new BigDecimal("1000.00"), now, "idem-" + id, "d".repeat(64), "e".repeat(64));
        report.markReceived();
        reports.saveAndFlush(report);
        return id;
    }

    private UUID store(UUID report, byte[] bytes, String mediaType, boolean upload, String storedSha256)
            throws Exception {
        UUID id = UUID.randomUUID();
        String key = "fan-reports/" + report + "/" + id;
        String marker = "missing-marker";
        if (upload) {
            marker = storage.put(key, new ValidatedImage(bytes, mediaType, bytes.length, sha256(bytes)));
            keys.add(key);
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        EvidenceAsset asset = new EvidenceAsset(id, report, key, mediaType, bytes.length,
                storedSha256 == null ? sha256(bytes) : storedSha256, now, now.plusDays(90));
        asset.markStored(marker);
        assets.saveAndFlush(asset);
        return id;
    }

    private String bearer(String subject) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), new JWTClaimsSet.Builder()
                .subject(subject + "-" + run).issuer(ADMIN_ISSUER).audience("ticket-defender-admin")
                .expirationTime(Date.from(Instant.now().plusSeconds(300))).build());
        jwt.sign(new RSASSASigner(keyPair.getPrivate()));
        return "Bearer " + jwt.serialize();
    }

    private static byte[] image(String format, int color) throws Exception {
        BufferedImage picture = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        picture.setRGB(1, 1, color);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(picture, format, output)).isTrue();
        return output.toByteArray();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
