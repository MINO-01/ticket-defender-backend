package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.out.persistence.AdminMembershipRepository;
import com.ticket.defender_core.adapter.out.persistence.MembershipEventGrantRepository;
import com.ticket.defender_core.adapter.out.persistence.MembershipRoleRepository;
import com.ticket.defender_core.adapter.out.persistence.SyntheticReservationImportTenantRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantEventRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantRepository;
import com.ticket.defender_core.adapter.out.persistence.VerifiedPerformanceRepository;
import com.ticket.defender_core.adapter.out.persistence.BulkLedgerImportJobRepository;
import com.ticket.defender_core.adapter.out.persistence.BulkLedgerImportScopeRepository;
import com.ticket.defender_core.application.service.BulkLedgerCandidatePreparationService;
import com.ticket.defender_core.domain.AccessRecordStatus;
import com.ticket.defender_core.domain.AdminMembershipEntity;
import com.ticket.defender_core.domain.MembershipEventGrant;
import com.ticket.defender_core.domain.MembershipRole;
import com.ticket.defender_core.domain.SyntheticReservationImportTenant;
import com.ticket.defender_core.domain.Tenant;
import com.ticket.defender_core.domain.TenantEvent;
import com.ticket.defender_core.domain.VerifiedPerformance;
import com.ticket.defender_core.domain.BulkLedgerImportJob;
import com.ticket.defender_core.domain.BulkLedgerImportScope;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** BL-01 HTTP and race contract against the disposable MySQL 8 CI database. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "ticket-defender.track2-candidate-import.enabled=true",
        "ticket-defender.track2-candidate-import.operational-mode=SYNTHETIC_ISOLATED",
        "ticket-defender.track2-candidate-import.synthetic-isolation-id=" + BulkLedgerImportHttpMySqlTest.ISOLATION_ID,
        "ticket-defender.track2-candidate-import.reference-hmac-master-key=synthetic-only-master-key-material-that-is-long-enough",
        "ticket-defender.track2-candidate-import.reference-hmac-key-version=v1"
})
@EnabledIfEnvironmentVariable(named = "TD_ISSUE32_MYSQL_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/td_issue32_(ci|bl01).*" )
class BulkLedgerImportHttpMySqlTest {
    static final String ISOLATION_ID = "dddddddd-dddd-4ddd-8ddd-dddddddddddd";
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
    @Autowired private BulkLedgerImportScopeRepository scopes;
    @Autowired private BulkLedgerCandidatePreparationService candidates;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean(name = "adminJwtDecoder") private JwtDecoder decoder;
    private final HttpClient client = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TD_ISSUE32_MYSQL_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("TD_ISSUE32_MYSQL_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("TD_ISSUE32_MYSQL_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }
    @BeforeEach void jwt() {
        when(decoder.decode(anyString())).thenAnswer(call -> Jwt.withTokenValue(call.getArgument(0)).header("alg", "RS256").subject(call.getArgument(0)).build());
    }

    @Test
    void tenantAdminRegistersReadsReplaysCancelsAndNeverExposesSensitiveFields() throws Exception {
        Fixture fixture = fixture(List.of(AdminRole.TENANT_ADMIN, AdminRole.REVIEWER));
        String performance = performance(fixture);
        String body = request("snapshot-a", "FULL_SNAPSHOT", true, performance, fixture.event().toString(), "2026-11-01T00:00:00Z", null, null, 3);
        String key = UUID.randomUUID().toString();
        HttpResponse<String> created = post("", body, key, fixture.subject());
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.headers().firstValue("Cache-Control")).contains("no-store");
        JsonNode job = JSON.readTree(created.body());
        assertThat(job.get("status").asText()).isEqualTo("CREATED");
        assertThat(created.body()).doesNotContain("idempotency", "requestDigest", "leaseOwner", "objectKey", "synthetic-only-master-key");
        assertThat(post("", body, key, fixture.subject()).body()).contains(job.get("importId").asText());
        assertThat(post("", body, UUID.randomUUID().toString(), fixture.subject()).body()).contains(job.get("importId").asText());
        assertThat(post("", request("different-key-contract", "FULL_SNAPSHOT", true, performance, fixture.event().toString(), "2026-11-01T00:00:00Z", null, null, 3), key, fixture.subject()).statusCode()).isEqualTo(409);
        assertThat(post("", body.replace("a".repeat(64), "b".repeat(64)), UUID.randomUUID().toString(), fixture.subject()).statusCode()).isEqualTo(409);
        assertThat(get("", fixture.subject()).body()).contains(job.get("importId").asText());
        assertThat(get("/" + job.get("importId").asText() + "/errors", fixture.subject()).body()).contains("\"items\":[]");
        assertThat(post("/" + job.get("importId").asText() + "/cancel", "{}", null, fixture.subject()).body()).contains("CANCELLED");
        assertThat(post("/" + job.get("importId").asText() + "/cancel", "{}", null, fixture.subject()).statusCode()).isEqualTo(409);
        // BL-02 implements this route; a same-tenant cancelled job must be rejected by its state transition,
        // not mistaken for an unknown resource and never receive another upload capability.
        assertThat(post("/" + job.get("importId").asText() + "/upload-session", "{}", null, fixture.subject()).statusCode()).isEqualTo(409);
    }

    @Test
    void rolesScopesAndModeMetadataAreFailClosed() throws Exception {
        Fixture admin = fixture(List.of(AdminRole.TENANT_ADMIN));
        Fixture analyst = fixtureInScope(admin, List.of(AdminRole.ANALYST));
        String performance = performance(admin);
        String valid = request("scope-full", "FULL_SNAPSHOT", true, performance, admin.event().toString(), "2026-11-01T00:00:00Z", null, null, 2);
        assertThat(post("", valid, UUID.randomUUID().toString(), analyst.subject()).statusCode()).isEqualTo(403);
        assertThat(post("", request("bad-full", "FULL_SNAPSHOT", true, performance, admin.event().toString(), null, "1", "2", 2), UUID.randomUUID().toString(), admin.subject()).statusCode()).isEqualTo(422);
        assertThat(post("", request("bad-change", "CHANGE_FEED", false, performance, admin.event().toString(), "2026-11-01T00:00:00Z", "1", "2", 2), UUID.randomUUID().toString(), admin.subject()).statusCode()).isEqualTo(422);
        assertThat(post("", request("change-ok", "CHANGE_FEED", false, performance, admin.event().toString(), null, "cursor-1", "cursor-2", 2), UUID.randomUUID().toString(), admin.subject()).statusCode()).isEqualTo(201);
        assertThat(post("", request("wrong-sum", "FULL_SNAPSHOT", true, performance, admin.event().toString(), "2026-11-01T00:00:00Z", null, null, 2, 3), UUID.randomUUID().toString(), admin.subject()).statusCode()).isEqualTo(422);
        Fixture foreign = fixture(List.of(AdminRole.TENANT_ADMIN));
        String foreignPerformance = performance(foreign);
        assertThat(post("", request("foreign", "FULL_SNAPSHOT", true, foreignPerformance, foreign.event().toString(), "2026-11-01T00:00:00Z", null, null, 2), UUID.randomUUID().toString(), admin.subject()).statusCode()).isEqualTo(404);
    }

    @Test
    void concurrentRegistrationCreatesAtMostOneJob() throws Exception {
        Fixture fixture = fixture(List.of(AdminRole.TENANT_ADMIN)); String performance = performance(fixture);
        String body = request("race", "FULL_SNAPSHOT", true, performance, fixture.event().toString(), "2026-11-01T00:00:00Z", null, null, 1);
        var pool = Executors.newFixedThreadPool(2);
        Future<HttpResponse<String>> one = pool.submit(() -> post("", body, UUID.randomUUID().toString(), fixture.subject()));
        Future<HttpResponse<String>> two = pool.submit(() -> post("", body, UUID.randomUUID().toString(), fixture.subject()));
        assertThat(one.get().statusCode()).isIn(201, 409); assertThat(two.get().statusCode()).isIn(201, 409);
        assertThat(jdbc.queryForObject("select count(*) from td_bulk_import_jobs where tenant_id=? and source_system='SYNTHETIC' and source_batch_id='race'", Integer.class, fixture.tenant().toString())).isEqualTo(1);
        pool.shutdown();
    }

    @Test
    void publishRequiresTenantAdminAndNeverExposesAnotherTenantsPublication() throws Exception {
        Fixture owner = fixture(List.of(AdminRole.TENANT_ADMIN));
        UUID performance = UUID.fromString(performance(owner));
        UUID importId = readyCandidate(owner, performance);
        HttpResponse<String> published = post("/" + importId + "/publish", "{}", UUID.randomUUID().toString(), owner.subject());
        assertThat(published.statusCode()).isEqualTo(200);
        assertThat(published.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(published.body()).contains(importId.toString(), "outboxPending").doesNotContain("object-", "synthetic-only-master-key");
        Fixture foreign = fixture(List.of(AdminRole.TENANT_ADMIN));
        assertThat(get("/" + importId + "/publication", foreign.subject()).statusCode()).isEqualTo(404);
        Fixture analyst = fixtureInScope(owner, List.of(AdminRole.ANALYST));
        assertThat(post("/" + importId + "/publish", "{}", UUID.randomUUID().toString(), analyst.subject()).statusCode()).isEqualTo(403);
    }

    private UUID readyCandidate(Fixture fixture, UUID performance) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        BulkLedgerImportJob job = new BulkLedgerImportJob(fixture.tenant(), fixture.membership(), UUID.randomUUID().toString().replace("-", "") + "a".repeat(32), "b".repeat(64),
                "v2", "SYNTHETIC", "publish-" + UUID.randomUUID(), BulkLedgerImportJob.Mode.FULL_SNAPSHOT, BulkLedgerImportJob.Format.CSV,
                "c".repeat(64), 1, 1, true, now);
        job.beginUpload("d".repeat(64), now.plusMinutes(1), now); job.completeUpload("object-version", now);
        job.startValidation("http-test", now.plusMinutes(1), now); job.beginStaging(job.getFenceToken(), now); job.readyToPublish(job.getFenceToken(), 1, 1, 0, now);
        jobs.saveAndFlush(job);
        scopes.saveAndFlush(new BulkLedgerImportScope(job.getId(), fixture.tenant(), fixture.event(), performance, now, 1, null, "cursor-10", null, 10L));
        jdbc.update("insert into td_bulk_import_staging_rows(id,import_id,tenant_id,event_id,performance_id,source_row_no,reservation_reference_hmac,ticket_reference_hmac,account_reference_hmac,hmac_key_version,purchased_at,ticket_quantity,quantity_unit,ticket_status,source_version,row_digest) values(?,?,?,?,?,?,?,?,?,?,utc_timestamp(6),1,'TICKET','ACTIVE','v-source',?)",
                UUID.randomUUID().toString(), job.getId().toString(), fixture.tenant().toString(), fixture.event().toString(), performance.toString(), 2,
                "r".repeat(64), "t".repeat(64), "a".repeat(64), "v1", "e".repeat(64));
        candidates.prepare(job.getId());
        assertThat(jdbc.queryForObject("select status from td_bulk_ledger_candidate_imports where import_id=?", String.class, job.getId().toString())).isEqualTo("READY");
        return job.getId();
    }

    private Fixture fixture(List<AdminRole> assigned) {
        UUID tenant = UUID.randomUUID(), event = UUID.randomUUID(), membership = UUID.randomUUID(); String subject = "bulk-" + membership;
        tenants.saveAndFlush(new Tenant(tenant, "Synthetic bulk tenant", AccessRecordStatus.ACTIVE));
        events.saveAndFlush(new TenantEvent(event, tenant, "bulk-" + event, "Synthetic bulk event", AccessRecordStatus.ACTIVE));
        memberships.saveAndFlush(new AdminMembershipEntity(membership, tenant, subject, AccessRecordStatus.ACTIVE));
        assigned.forEach(role -> roles.saveAndFlush(new MembershipRole(UUID.randomUUID(), membership, role)));
        grants.saveAndFlush(new MembershipEventGrant(UUID.randomUUID(), membership, tenant, event));
        syntheticTenants.saveAndFlush(new SyntheticReservationImportTenant(tenant, UUID.fromString(ISOLATION_ID), LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1)));
        return new Fixture(tenant, event, membership, subject);
    }
    private Fixture fixtureInScope(Fixture scope, List<AdminRole> assigned) {
        UUID membership = UUID.randomUUID(); String subject = "bulk-" + membership;
        memberships.saveAndFlush(new AdminMembershipEntity(membership, scope.tenant(), subject, AccessRecordStatus.ACTIVE));
        assigned.forEach(role -> roles.saveAndFlush(new MembershipRole(UUID.randomUUID(), membership, role)));
        grants.saveAndFlush(new MembershipEventGrant(UUID.randomUUID(), membership, scope.tenant(), scope.event()));
        return new Fixture(scope.tenant(), scope.event(), membership, subject);
    }
    private String performance(Fixture fixture) {
        UUID id = UUID.randomUUID(); performances.saveAndFlush(new VerifiedPerformance(id, fixture.tenant(), fixture.event(), "p-" + id, "synthetic", "v1", fixture.membership(), LocalDateTime.now(ZoneOffset.UTC))); return id.toString();
    }
    private static String request(String batch, String mode, boolean complete, String performance, String event, String asOf, String start, String end, long rows) throws Exception {
        return request(batch, mode, complete, performance, event, asOf, start, end, rows, rows);
    }
    private static String request(String batch, String mode, boolean complete, String performance, String event, String asOf, String start, String end, long scopeRows, long declaredTotal) throws Exception {
        Map<String, Object> scope = new java.util.LinkedHashMap<>(); scope.put("eventId", event); scope.put("performanceId", performance); scope.put("declaredRowCount", scopeRows);
        if (asOf != null) scope.put("scopeAsOf", asOf); if (start != null) scope.put("cursorStart", start); if (end != null) scope.put("cursorEnd", end);
        return JSON.writeValueAsString(Map.of("schemaVersion", "v1", "sourceSystem", "SYNTHETIC", "sourceBatchId", batch, "mode", mode, "format", "CSV", "declaredObjectSha256", "a".repeat(64), "declaredFileBytes", scopeRows, "declaredRowCount", declaredTotal, "completeSnapshot", complete, "scopes", List.of(scope)));
    }
    private HttpResponse<String> post(String suffix, String body, String key, String subject) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base() + suffix)).POST(HttpRequest.BodyPublishers.ofString(body)).header("Authorization", "Bearer " + subject).header("Content-Type", "application/json");
        if (key != null) request.header("Idempotency-Key", key); return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> get(String suffix, String subject) throws Exception { return client.send(HttpRequest.newBuilder(URI.create(base() + suffix)).GET().header("Authorization", "Bearer " + subject).build(), HttpResponse.BodyHandlers.ofString()); }
    private String base() { return "http://127.0.0.1:" + port + "/api/v1/admin/ledger-imports"; }
    private record Fixture(UUID tenant, UUID event, UUID membership, String subject) { }
}
