package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.out.persistence.AdminMembershipRepository;
import com.ticket.defender_core.adapter.out.persistence.MembershipEventGrantRepository;
import com.ticket.defender_core.adapter.out.persistence.MembershipRoleRepository;
import com.ticket.defender_core.adapter.out.persistence.SyntheticReservationImportTenantRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantEventRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantRepository;
import com.ticket.defender_core.adapter.out.persistence.VerifiedPerformanceRepository;
import com.ticket.defender_core.application.service.BulkLedgerImportWorker;
import com.ticket.defender_core.application.service.BulkLedgerUploadDeletionService;
import com.ticket.defender_core.application.service.BulkLedgerUploadStorage;
import com.ticket.defender_core.domain.AccessRecordStatus;
import com.ticket.defender_core.domain.AdminMembershipEntity;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 합성 원본 v3 파일로 등록, 업로드, 스테이징, 후보, 공개, 신호 저장, 원본 삭제까지 끝까지 확인합니다.
 * 저장소는 메모리 대체 구현이며, 모든 원본 값은 문서용 예약 대역 IP와 존재할 수 없는 연락처, 계좌, 카드, 주소입니다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "ticket-defender.track2-candidate-import.enabled=true",
        "ticket-defender.track2-candidate-import.operational-mode=SYNTHETIC_ISOLATED",
        "ticket-defender.track2-candidate-import.synthetic-isolation-id=" + BulkLedgerV3RawIntakeHttpMySqlTest.ISOLATION_ID,
        "ticket-defender.track2-candidate-import.reference-hmac-master-key=synthetic-only-master-key-material-that-is-long-enough",
        "ticket-defender.track2-candidate-import.reference-hmac-key-version=v1",
        "ticket-defender.bulk-ledger-upload.v3-raw-intake.enabled=true",
        "ticket-defender.bulk-ledger-upload.cleanup.enabled=true",
        "ticket-defender.track1.ip-network-classes=203.0.113.0/24=CORPORATE_EDU"
})
@EnabledIfEnvironmentVariable(named = "TD_ISSUE32_MYSQL_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/td_issue32_(ci|bl01).*")
@ExtendWith(OutputCaptureExtension.class)
class BulkLedgerV3RawIntakeHttpMySqlTest {
    static final String ISOLATION_ID = "abababab-abab-4bab-8bab-abababababab";
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final String HEADER = "event_id,performance_id,reservation_reference,ticket_reference,account_reference,purchased_at,ticket_quantity,"
            + "quantity_unit,ticket_status,source_version,device_reference,ip_reference,ip_network_type,delivery_address,recipient_phone,member_phone,"
            + "payment_masked_card,payment_issuer_code,payment_method_type,refund_bank_code,refund_account_number";
    /** DB 전체 검색과 로그 검사에 쓰는 원본 문자열입니다. 다른 테스트의 UUID와 겹치지 않는 형태만 고릅니다. */
    private static final List<String> RAW = List.of("192.0.2.71", "203.0.113.72", "198.51.100.73", "synthetic-device-raw-0071",
            "가상시 가상구 모의로 71", "000-0000-0071", "000-0000-0072", "999999******0071", "000-777-000-111", "000777000111", "999999999990071");

    @LocalServerPort private int port;
    @Autowired private TenantRepository tenants;
    @Autowired private TenantEventRepository events;
    @Autowired private AdminMembershipRepository memberships;
    @Autowired private MembershipRoleRepository roles;
    @Autowired private MembershipEventGrantRepository grants;
    @Autowired private SyntheticReservationImportTenantRepository syntheticTenants;
    @Autowired private VerifiedPerformanceRepository performances;
    @Autowired private BulkLedgerImportWorker worker;
    @Autowired private BulkLedgerUploadDeletionService deletions;
    @Autowired private MemoryUploadStorage storage;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean(name = "adminJwtDecoder") private JwtDecoder decoder;
    private final HttpClient client = HttpClient.newHttpClient();

    @TestConfiguration
    static class Storage {
        @Bean @Primary MemoryUploadStorage memoryUploadStorage() { return new MemoryUploadStorage(); }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TD_ISSUE32_MYSQL_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("TD_ISSUE32_MYSQL_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("TD_ISSUE32_MYSQL_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @BeforeEach void jwt() {
        when(decoder.decode(anyString())).thenAnswer(call -> Jwt.withTokenValue(call.getArgument(0)).header("alg", "RS256").subject(call.getArgument(0)).build());
        storage.failDeletes = false;
    }

    @Test
    void syntheticRawV3FileTravelsToPublishedSignalsWithoutLeavingRawValuesAnywhere(CapturedOutput output) throws Exception {
        Fixture fixture = fixture();
        UUID first = performance(fixture), second = performance(fixture);
        String csv = HEADER + "\n"
                + row(fixture.event(), first, "res-1", "tkt-1", "acct-1", "synthetic-device-raw-0071", "192.0.2.71", "residential", "가상시 가상구 모의로 71",
                "000-0000-0071", "", "999999******0071", "zz", "card", "999", "000-777-000-111")
                + row(fixture.event(), first, "res-2", "tkt-2", "acct-2", "synthetic-device-raw-0071", "203.0.113.72", "", "가상시  가상구 모의로 71 (101동)",
                "", "000-0000-0072", "9999 99** **** 0071", "ZZ", "CARD", "999", "000777000111")
                + row(fixture.event(), second, "res-3", "tkt-3", "acct-1", "synthetic-device-raw-0071", "198.51.100.73", "", "", "", "", "", "", "", "", "")
                + row(fixture.event(), second, "res-4", "tkt-4", "acct-2", "synthetic-device-raw-0071", "198.51.100.73", "", "", "", "", "", "", "", "", "");
        UUID importId = upload(fixture, csv, Map.of(first, 2L, second, 2L), "v3-e2e");

        worker.process(importId);
        assertThat(jdbc.queryForObject("select status from td_bulk_import_jobs where id=?", String.class, importId.toString())).isEqualTo("READY_TO_PUBLISH");
        assertThat(jdbc.queryForObject("select status from td_bulk_ledger_candidate_imports where import_id=?", String.class, importId.toString())).isEqualTo("READY");
        assertThat(storage.objects).doesNotContainKey(key(importId));
        assertThat(jdbc.queryForObject("select status from td_bulk_import_upload_deletions where import_id=?", String.class, importId.toString())).isEqualTo("DELETED");

        HttpResponse<String> published = post("/" + importId + "/publish", "{}", UUID.randomUUID().toString(), fixture.subject());
        assertThat(published.statusCode()).isEqualTo(200);

        List<Map<String, Object>> signals = jdbc.queryForList("""
                select s.ticket_reference_hmac,s.hmac_purpose,s.hmac_key_version,s.device_hmac,s.ip_hmac,s.ip_network_type,s.ip_network_type_source,
                       s.address_hmac,s.phone_hmac,s.phone_source,s.payment_hmac,s.refund_account_hmac,s.version_id,v.performance_id
                from td_bulk_ledger_track1_ticket_signals s join td_bulk_ledger_versions v on v.id=s.version_id
                where v.import_id=? order by v.performance_id,s.ip_network_type_source
                """, importId.toString());
        assertThat(signals).hasSize(4);
        assertThat(signals).allSatisfy(row -> {
            assertThat(row.get("hmac_purpose")).isEqualTo("TRACK1_CONNECTION");
            assertThat(row.get("hmac_key_version")).isEqualTo("v1");
            assertThat(row.get("device_hmac")).isEqualTo(signals.getFirst().get("device_hmac"));
        });
        var firstScope = signals.stream().filter(row -> first.toString().equals(row.get("performance_id"))).toList();
        assertThat(firstScope).extracting(row -> row.get("ip_network_type_source")).containsExactlyInAnyOrder("DECLARED", "CLASSIFIED");
        assertThat(firstScope).extracting(row -> row.get("ip_network_type")).containsExactlyInAnyOrder("RESIDENTIAL", "CORPORATE_EDU");
        assertThat(firstScope).extracting(row -> row.get("phone_source")).containsExactlyInAnyOrder("RECIPIENT", "MEMBER");
        assertThat(firstScope.get(0).get("address_hmac")).isEqualTo(firstScope.get(1).get("address_hmac"));
        assertThat(firstScope.get(0).get("payment_hmac")).isEqualTo(firstScope.get(1).get("payment_hmac"));
        assertThat(firstScope.get(0).get("refund_account_hmac")).isEqualTo(firstScope.get(1).get("refund_account_hmac"));
        assertThat(firstScope.get(0).get("device_hmac")).isNotEqualTo(firstScope.get(0).get("address_hmac"));
        var secondScope = signals.stream().filter(row -> second.toString().equals(row.get("performance_id"))).toList();
        assertThat(secondScope).allSatisfy(row -> {
            assertThat(row.get("ip_network_type_source")).isEqualTo("UNKNOWN");
            assertThat(row.get("ip_hmac")).isEqualTo(secondScope.getFirst().get("ip_hmac"));
            assertThat(row.get("phone_hmac")).isNull();
        });

        // 기존 HMAC 신호 등록 API로 같은 티켓에 다른 값을 보내면 기존 규칙대로 충돌입니다.
        Map<String, Object> conflict = new LinkedHashMap<>();
        conflict.put("ticketReferenceHmac", firstScope.getFirst().get("ticket_reference_hmac"));
        conflict.put("hmacKeyVersion", "v1");
        conflict.put("paymentHmac", "0".repeat(64));
        conflict.put("sourceVersion", "source-v1");
        HttpResponse<String> conflicted = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/admin/events/" + fixture.event()
                        + "/ledger-track1/versions/" + firstScope.getFirst().get("version_id") + "/signals"))
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(List.of(conflict))))
                .header("Authorization", "Bearer " + fixture.subject()).header("Content-Type", "application/json").build(), HttpResponse.BodyHandlers.ofString());
        assertThat(conflicted.statusCode()).isEqualTo(409);
        assertThat(conflicted.body()).contains("LEDGER_SIGNAL_ALREADY_RECORDED");

        assertNoRawAnywhere(output, published.body());
    }

    @Test
    void unmaskedCardBrokenCsvAndIpv6AreRowErrorsWhoseRawValuesNeverReachResponsesLogsOrTheDatabase(CapturedOutput output) throws Exception {
        Fixture fixture = fixture();
        UUID performance = performance(fixture);
        String unmasked = HEADER + "\n" + row(fixture.event(), performance, "res-u", "tkt-u", "acct-u", "", "192.0.2.71", "", "", "", "",
                "999999999990071", "ZZ", "CARD", "", "");
        String ipv6 = HEADER + "\n" + row(fixture.event(), performance, "res-6", "tkt-6", "acct-6", "", "2001:db8::71", "", "", "", "", "", "", "", "", "");
        String broken = HEADER + "\n" + fixture.event() + "," + performance + ",res-b,tkt-b,acct-b,2026-11-13T10:00:00Z,1,TICKET,ACTIVE,source-v1,,192.0.2.71,,\"가상시 가상구 모의로 71\n";
        Map<String, String> expected = Map.of("unmasked", "BULK_CSV_PAYMENT_UNMASKED", "ipv6", "BULK_CSV_IP_INVALID", "broken", "BULK_CSV_INVALID_UTF8_OR_SYNTAX");
        Map<String, String> files = Map.of("unmasked", unmasked, "ipv6", ipv6, "broken", broken);
        StringBuilder responses = new StringBuilder();
        for (var entry : files.entrySet()) {
            UUID importId = upload(fixture, entry.getValue(), Map.of(performance, 1L), "v3-error-" + entry.getKey());
            worker.process(importId);
            assertThat(jdbc.queryForObject("select status from td_bulk_import_jobs where id=?", String.class, importId.toString())).isEqualTo("REJECTED");
            HttpResponse<String> errors = get("/" + importId + "/errors", fixture.subject());
            assertThat(errors.body()).contains(expected.get(entry.getKey()));
            responses.append(errors.body());
            assertThat(storage.objects).doesNotContainKey(key(importId));
        }
        assertNoRawAnywhere(output, responses.toString(), "2001:db8::71");
    }

    @Test
    void deletionFailuresAfterThreeAttemptsAreVisibleInTheAdminStatus() throws Exception {
        Fixture fixture = fixture();
        UUID performance = performance(fixture);
        storage.failDeletes = true;
        UUID importId = upload(fixture, HEADER + "\n" + row(fixture.event(), performance, "res-d", "tkt-d", "acct-d", "", "192.0.2.71", "", "", "", "", "", "", "", "", ""),
                Map.of(performance, 1L), "v3-delete-failure");
        worker.process(importId);
        assertThat(jdbc.queryForObject("select status from td_bulk_import_jobs where id=?", String.class, importId.toString())).isEqualTo("READY_TO_PUBLISH");
        assertThat(jdbc.queryForObject("select attempt_count from td_bulk_import_upload_deletions where import_id=?", Integer.class, importId.toString())).isEqualTo(3);
        JsonNode status = JSON.readTree(get("/upload-deletions/status", fixture.subject()).body());
        assertThat(status.get("v3RawIntakeAccepted").asBoolean()).isTrue();
        assertThat(status.get("failedAfterRetriesCount").asLong()).isEqualTo(1);
        assertThat(status.get("maxAttempts").asInt()).isEqualTo(3);
        storage.failDeletes = false;
    }

    @Test
    void retentionCleanupMakesAnUnprocessedUploadFailWithAFinalCode() throws Exception {
        Fixture fixture = fixture();
        UUID performance = performance(fixture);
        UUID importId = upload(fixture, HEADER + "\n" + row(fixture.event(), performance, "res-x", "tkt-x", "acct-x", "", "192.0.2.71", "", "", "", "", "", "", "", "", ""),
                Map.of(performance, 1L), "v3-retention");
        jdbc.update("update td_bulk_import_jobs set created_at=date_sub(utc_timestamp(6), interval 25 hour) where id=?", importId.toString());

        deletions.cleanupExpired();

        assertThat(storage.objects).doesNotContainKey(key(importId));
        assertThat(jdbc.queryForMap("select status,trigger_reason from td_bulk_import_upload_deletions where import_id=?", importId.toString()))
                .containsEntry("status", "DELETED").containsEntry("trigger_reason", "RETENTION_EXPIRED");
        HttpResponse<String> completeAgain = post("/" + importId + "/complete-upload", "{}", null, fixture.subject());
        assertThat(completeAgain.statusCode()).isEqualTo(409);
        assertThat(completeAgain.body()).contains("UPLOAD_OBJECT_EXPIRED");

        worker.process(importId);
        assertThat(jdbc.queryForObject("select status from td_bulk_import_jobs where id=?", String.class, importId.toString())).isEqualTo("REJECTED");
        assertThat(get("/" + importId + "/errors", fixture.subject()).body()).contains("UPLOAD_OBJECT_EXPIRED");
    }

    private void assertNoRawAnywhere(CapturedOutput output, String responses, String... extra) {
        List<String> raws = new java.util.ArrayList<>(RAW);
        raws.addAll(List.of(extra));
        for (String raw : raws) {
            assertThat(output.getAll()).as("log").doesNotContain(raw);
            assertThat(responses).as("response").doesNotContain(raw);
        }
        List<Map<String, Object>> columns = jdbc.queryForList("""
                select table_name,column_name from information_schema.columns
                where table_schema=database() and data_type in ('char','varchar','tinytext','text','mediumtext','longtext','json')
                """);
        for (Map<String, Object> column : columns) {
            String table = String.valueOf(column.get("table_name")), name = String.valueOf(column.get("column_name"));
            String where = String.join(" or ", java.util.Collections.nCopies(raws.size(), "cast(`" + name + "` as char) like ?"));
            Object[] patterns = raws.stream().map(raw -> "%" + raw + "%").toArray();
            Integer found = jdbc.queryForObject("select count(*) from `" + table + "` where " + where, Integer.class, patterns);
            assertThat(found).as(table + "." + name).isZero();
        }
    }

    private UUID upload(Fixture fixture, String csv, Map<UUID, Long> rowsByPerformance, String batch) throws Exception {
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        List<Map<String, Object>> scopes = new java.util.ArrayList<>();
        long total = 0;
        for (var entry : rowsByPerformance.entrySet()) {
            Map<String, Object> scope = new LinkedHashMap<>();
            scope.put("eventId", fixture.event().toString()); scope.put("performanceId", entry.getKey().toString());
            scope.put("declaredRowCount", entry.getValue()); scope.put("scopeAsOf", "2026-11-14T00:00:00Z");
            scope.put("cursorEnd", "cursor-" + batch); scope.put("sourceSequenceEnd", 10);
            scopes.add(scope); total += entry.getValue();
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("schemaVersion", "v3"); request.put("sourceSystem", "SYNTHETIC"); request.put("sourceBatchId", batch + "-" + UUID.randomUUID());
        request.put("mode", "FULL_SNAPSHOT"); request.put("format", "CSV"); request.put("declaredObjectSha256", sha);
        request.put("declaredFileBytes", bytes.length); request.put("declaredRowCount", total); request.put("completeSnapshot", true); request.put("scopes", scopes);
        HttpResponse<String> created = post("", JSON.writeValueAsString(request), UUID.randomUUID().toString(), fixture.subject());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        UUID importId = UUID.fromString(JSON.readTree(created.body()).get("importId").asText());
        assertThat(post("/" + importId + "/upload-session", "{}", null, fixture.subject()).statusCode()).isEqualTo(201);
        storage.objects.put(key(importId), bytes);
        HttpResponse<String> completed = post("/" + importId + "/complete-upload", "{}", null, fixture.subject());
        assertThat(completed.statusCode()).as(completed.body()).isEqualTo(200);
        return importId;
    }

    private static String row(UUID event, UUID performance, String reservation, String ticket, String account, String device, String ip, String networkType,
                              String address, String recipient, String member, String card, String issuer, String method, String bank, String refund) {
        return String.join(",", event.toString(), performance.toString(), reservation, ticket, account, "2026-11-13T10:00:00Z", "1", "TICKET", "ACTIVE",
                "source-v1", device, ip, networkType, address, recipient, member, card, issuer, method, bank, refund) + "\n";
    }

    private static String key(UUID importId) { return "bulk-ledger-private/" + importId + "/upload.csv"; }

    private Fixture fixture() {
        UUID tenant = UUID.randomUUID(), event = UUID.randomUUID(), membership = UUID.randomUUID();
        String subject = "bulk-v3-" + membership;
        tenants.saveAndFlush(new Tenant(tenant, "Synthetic v3 tenant", AccessRecordStatus.ACTIVE));
        events.saveAndFlush(new TenantEvent(event, tenant, "bulk-v3-" + event, "Synthetic v3 event", AccessRecordStatus.ACTIVE));
        memberships.saveAndFlush(new AdminMembershipEntity(membership, tenant, subject, AccessRecordStatus.ACTIVE));
        roles.saveAndFlush(new MembershipRole(UUID.randomUUID(), membership, AdminRole.TENANT_ADMIN));
        grants.saveAndFlush(new MembershipEventGrant(UUID.randomUUID(), membership, tenant, event));
        syntheticTenants.saveAndFlush(new SyntheticReservationImportTenant(tenant, UUID.fromString(ISOLATION_ID), LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1)));
        return new Fixture(tenant, event, membership, subject);
    }

    private UUID performance(Fixture fixture) {
        UUID id = UUID.randomUUID();
        performances.saveAndFlush(new VerifiedPerformance(id, fixture.tenant(), fixture.event(), "p-" + id, "synthetic", "v1", fixture.membership(), LocalDateTime.now(ZoneOffset.UTC)));
        return id;
    }

    private HttpResponse<String> post(String suffix, String body, String key, String subject) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base() + suffix)).POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Authorization", "Bearer " + subject).header("Content-Type", "application/json");
        if (key != null) request.header("Idempotency-Key", key);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String suffix, String subject) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base() + suffix)).GET().header("Authorization", "Bearer " + subject).build(), HttpResponse.BodyHandlers.ofString());
    }

    private String base() { return "http://127.0.0.1:" + port + "/api/v1/admin/ledger-imports"; }

    private record Fixture(UUID tenant, UUID event, UUID membership, String subject) { }

    /** 버전 관리 버킷을 흉내 내는 메모리 저장소입니다. 실제 MinIO 정책을 바꾸지 않고 삭제 경로를 검증합니다. */
    static final class MemoryUploadStorage implements BulkLedgerUploadStorage {
        final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        volatile boolean failDeletes;

        @Override public UploadSession createUploadSession(String key, long bytes, Instant expiresAt) {
            return new UploadSession(URI.create("https://upload.invalid/presigned"), expiresAt);
        }
        @Override public VerifiedObject verify(String key, String expectedVersionId, long maximumBytes) {
            byte[] bytes = objects.get(key);
            if (bytes == null) throw new InvalidObject();
            if (expectedVersionId != null && !expectedVersionId.equals(version(key))) throw new ObjectChanged();
            try {
                return new VerifiedObject(version(key), bytes.length, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        }
        @Override public InputStream openVersion(String key, String versionId) {
            byte[] bytes = objects.get(key);
            if (bytes == null || !version(key).equals(versionId)) throw new ObjectChanged();
            return new ByteArrayInputStream(bytes);
        }
        @Override public VerifiedObject putImmutable(String name, InputStream content, long bytes, String sha256, Runnable progress) {
            throw new Unavailable("not used by this test");
        }
        @Override public void deleteAllVersions(String key) {
            if (failDeletes) throw new Unavailable("synthetic delete denied");
            objects.remove(key);
        }
        @Override public boolean verifyDeletePermission() { return true; }
        private static String version(String key) { return "memory-" + Integer.toHexString(key.hashCode()); }
    }
}
