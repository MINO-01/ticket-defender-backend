package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.out.persistence.AdminMembershipRepository;
import com.ticket.defender_core.adapter.out.persistence.BulkLedgerImportJobRepository;
import com.ticket.defender_core.adapter.out.persistence.MembershipEventGrantRepository;
import com.ticket.defender_core.adapter.out.persistence.MembershipRoleRepository;
import com.ticket.defender_core.adapter.out.persistence.SyntheticReservationImportTenantRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantEventRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantRepository;
import com.ticket.defender_core.adapter.out.persistence.VerifiedPerformanceRepository;
import com.ticket.defender_core.application.service.BulkLedgerCsvTestData;
import com.ticket.defender_core.application.service.BulkLedgerImportWorker;
import com.ticket.defender_core.application.service.BulkLedgerUploadStorage;
import com.ticket.defender_core.domain.AccessRecordStatus;
import com.ticket.defender_core.domain.AdminMembershipEntity;
import com.ticket.defender_core.domain.BulkLedgerImportJob;
import com.ticket.defender_core.domain.MembershipEventGrant;
import com.ticket.defender_core.domain.MembershipRole;
import com.ticket.defender_core.domain.SyntheticReservationImportTenant;
import com.ticket.defender_core.domain.Tenant;
import com.ticket.defender_core.domain.TenantEvent;
import com.ticket.defender_core.domain.VerifiedPerformance;
import com.ticket.defender_core.security.AdminRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** BL-02 end-to-end upload and staging path against the disposable MinIO and MySQL CI services. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "ticket-defender.track2-candidate-import.enabled=true",
        "ticket-defender.track2-candidate-import.operational-mode=SYNTHETIC_ISOLATED",
        "ticket-defender.track2-candidate-import.synthetic-isolation-id=" + BulkLedgerStagingMinioMySqlTest.ISOLATION_ID,
        "ticket-defender.track2-candidate-import.reference-hmac-master-key=synthetic-only-master-key-material-that-is-long-enough",
        "ticket-defender.track2-candidate-import.reference-hmac-key-version=v1",
        "ticket-defender.bulk-ledger-upload.enabled=true",
        "ticket-defender.bulk-ledger-upload.worker-enabled=false"
})
@EnabledIfEnvironmentVariable(named = "TD_ISSUE32_MINIO_ENDPOINT", matches = "http://127\\.0\\.0\\.1:19000")
@ExtendWith(OutputCaptureExtension.class)
class BulkLedgerStagingMinioMySqlTest {
    static final String ISOLATION_ID = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee";
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    @LocalServerPort private int port;
    @Autowired private TenantRepository tenants;
    @Autowired private TenantEventRepository events;
    @Autowired private AdminMembershipRepository memberships;
    @Autowired private MembershipRoleRepository roles;
    @Autowired private MembershipEventGrantRepository grants;
    @Autowired private SyntheticReservationImportTenantRepository syntheticTenants;
    @Autowired private VerifiedPerformanceRepository performances;
    @Autowired private BulkLedgerImportJobRepository jobs;
    @Autowired private BulkLedgerImportWorker worker;
    @Autowired private BulkLedgerUploadStorage storage;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean(name = "adminJwtDecoder") private JwtDecoder decoder;
    private final HttpClient client = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> required("TD_ISSUE32_MYSQL_URL"));
        registry.add("spring.datasource.username", () -> required("TD_ISSUE32_MYSQL_USER"));
        registry.add("spring.datasource.password", () -> required("TD_ISSUE32_MYSQL_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("ticket-defender.bulk-ledger-upload.storage.endpoint", () -> required("TD_ISSUE32_MINIO_ENDPOINT"));
        registry.add("ticket-defender.bulk-ledger-upload.storage.bucket", () -> required("TD_ISSUE88_MINIO_BUCKET"));
        registry.add("ticket-defender.bulk-ledger-upload.storage.access-key", () -> required("TD_ISSUE88_MINIO_ACCESS_KEY"));
        registry.add("ticket-defender.bulk-ledger-upload.storage.secret-key", () -> required("TD_ISSUE88_MINIO_SECRET_KEY"));
    }

    @BeforeEach
    void jwt() {
        when(decoder.decode(anyString())).thenAnswer(call -> Jwt.withTokenValue(call.getArgument(0)).header("alg", "RS256")
                .subject(call.getArgument(0)).build());
    }

    @Test
    void presignedUploadPinsVersionAndStagesFiftyThousandRowsWithoutPublishing(CapturedOutput output) throws Exception {
        Fixture fixture = fixture();
        UUID performanceId = performance(fixture);
        byte[] csv = csv(fixture.event(), performanceId);
        String digest = sha256(csv);
        String registration = JSON.writeValueAsString(Map.of("schemaVersion", "v1", "sourceSystem", "SYNTHETIC",
                "sourceBatchId", "bl02-" + UUID.randomUUID(), "mode", "FULL_SNAPSHOT", "format", "CSV",
                "declaredObjectSha256", digest, "declaredFileBytes", csv.length, "declaredRowCount", 50_000,
                "completeSnapshot", true, "scopes", List.of(Map.of("eventId", fixture.event().toString(),
                        "performanceId", performanceId.toString(), "scopeAsOf", "2026-11-13T10:00:00Z", "declaredRowCount", 50_000))));
        JsonNode registered = JSON.readTree(post("", registration, UUID.randomUUID().toString(), fixture.subject()).body());
        UUID importId = UUID.fromString(registered.get("importId").asText());
        JsonNode session = JSON.readTree(post("/" + importId + "/upload-session", "{}", null, fixture.subject()).body());
        HttpResponse<Void> uploaded = client.send(HttpRequest.newBuilder(URI.create(session.get("uploadUrl").asText()))
                .header("Content-Type", "text/csv").PUT(HttpRequest.BodyPublishers.ofByteArray(csv)).build(), HttpResponse.BodyHandlers.discarding());
        assertThat(uploaded.statusCode()).isIn(200, 204);
        JsonNode completed = JSON.readTree(post("/" + importId + "/complete-upload", "{}", null, fixture.subject()).body());
        assertThat(completed.get("status").asText()).isEqualTo("UPLOADED");

        BulkLedgerImportJob uploadedJob = jobs.findById(importId).orElseThrow();
        assertThat(uploadedJob.getUploadObjectVersion()).isNotBlank();
        BulkLedgerUploadStorage.VerifiedObject fixed = storage.verify("bulk-ledger-private/" + importId + "/upload.csv",
                uploadedJob.getUploadObjectVersion(), csv.length);
        assertThat(fixed.byteSize()).isEqualTo(csv.length);
        assertThat(fixed.sha256()).isEqualTo(digest);
        try (InputStream source = storage.openVersion("bulk-ledger-private/" + importId + "/upload.csv", uploadedJob.getUploadObjectVersion())) {
            assertThat(source.read()).isEqualTo(Byte.toUnsignedInt(csv[0]));
        }

        worker.process(importId);
        BulkLedgerImportJob ready = jobs.findById(importId).orElseThrow();
        long stagedRows = jdbc.queryForObject("select count(*) from td_bulk_import_staging_rows where import_id=?", Long.class, importId.toString());
        long errorRows = jdbc.queryForObject("select count(*) from td_bulk_import_errors where job_id=?", Long.class, importId.toString());
        assertThat(ready.getStatus()).as("stagedRows=%s, errorRows=%s, workerLog=%s", stagedRows, errorRows, tail(output.getOut()))
                .isEqualTo(BulkLedgerImportJob.Status.READY_TO_PUBLISH);
        assertThat(ready.getProcessedRowCount()).isEqualTo(50_000);
        assertThat(ready.getAcceptedRowCount()).isEqualTo(50_000);
        assertThat(ready.getRejectedRowCount()).isZero();
        assertThat(stagedRows).isEqualTo(50_000L);
        assertThat(jdbc.queryForObject("select count(*) from td_bulk_import_staging_rows where import_id=? and event_id=? and performance_id=?",
                Long.class, importId.toString(), fixture.event().toString(), performanceId.toString())).isEqualTo(50_000L);
        worker.process(importId);
        assertThat(jdbc.queryForObject("select count(*) from td_bulk_import_staging_rows where import_id=?", Long.class, importId.toString()))
                .isEqualTo(50_000L);
    }

    private Fixture fixture() {
        UUID tenant = UUID.randomUUID(), event = UUID.randomUUID(), membership = UUID.randomUUID();
        String subject = "bulk-staging-" + membership;
        tenants.saveAndFlush(new Tenant(tenant, "Synthetic bulk staging tenant", AccessRecordStatus.ACTIVE));
        events.saveAndFlush(new TenantEvent(event, tenant, "bulk-staging-" + event, "Synthetic bulk staging event", AccessRecordStatus.ACTIVE));
        memberships.saveAndFlush(new AdminMembershipEntity(membership, tenant, subject, AccessRecordStatus.ACTIVE));
        roles.saveAndFlush(new MembershipRole(UUID.randomUUID(), membership, AdminRole.TENANT_ADMIN));
        grants.saveAndFlush(new MembershipEventGrant(UUID.randomUUID(), membership, tenant, event));
        syntheticTenants.saveAndFlush(new SyntheticReservationImportTenant(tenant, UUID.fromString(ISOLATION_ID),
                LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1)));
        return new Fixture(tenant, event, membership, subject);
    }

    private UUID performance(Fixture fixture) {
        UUID id = UUID.randomUUID();
        performances.saveAndFlush(new VerifiedPerformance(id, fixture.tenant(), fixture.event(), "performance-" + id,
                "synthetic", "v1", fixture.membership(), LocalDateTime.now(ZoneOffset.UTC)));
        return id;
    }

    private static byte[] csv(UUID event, UUID performance) throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            BulkLedgerCsvTestData.writeFullSnapshot(output, event, performance, 50_000);
            return output.toByteArray();
        }
    }

    private HttpResponse<String> post(String suffix, String body, String idempotencyKey, String subject) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base() + suffix)).POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Authorization", "Bearer " + subject).header("Content-Type", "application/json");
        if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String base() { return "http://127.0.0.1:" + port + "/api/v1/admin/ledger-imports"; }
    private static String sha256(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
    private static String tail(String value) { return value.substring(Math.max(0, value.length() - 4_000)); }
    private record Fixture(UUID tenant, UUID event, UUID membership, String subject) { }
}
