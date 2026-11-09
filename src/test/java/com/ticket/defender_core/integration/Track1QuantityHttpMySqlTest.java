package com.ticket.defender_core.integration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpServer;
import com.ticket.defender_core.adapter.out.persistence.AdminMembershipRepository;
import com.ticket.defender_core.adapter.out.persistence.MembershipEventGrantRepository;
import com.ticket.defender_core.adapter.out.persistence.MembershipRoleRepository;
import com.ticket.defender_core.adapter.out.persistence.SyntheticReservationImportTenantRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantEventRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantRepository;
import com.ticket.defender_core.domain.AccessRecordStatus;
import com.ticket.defender_core.domain.AdminMembershipEntity;
import com.ticket.defender_core.domain.MembershipEventGrant;
import com.ticket.defender_core.domain.MembershipRole;
import com.ticket.defender_core.domain.SyntheticReservationImportTenant;
import com.ticket.defender_core.domain.Tenant;
import com.ticket.defender_core.domain.TenantEvent;
import com.ticket.defender_core.security.AdminRole;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** 격리 MySQL과 실제 HTTP 경계로 원장 반입부터 v2 실행까지 검증합니다. Data는 계약을 따르는 합성 스텁이며 실제 품질 검증이 아닙니다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "ticket-defender.track1-v2.enabled=true",
        "ticket-defender.track1-internal-auth.shared-secret=synthetic-track1-secret-value-for-tests",
        "ticket-defender.track2-candidate-import.enabled=true",
        "ticket-defender.track2-candidate-import.operational-mode=SYNTHETIC_ISOLATED",
        "ticket-defender.track2-candidate-import.synthetic-isolation-id=" + Track1QuantityHttpMySqlTest.ISOLATION_ID,
        "ticket-defender.track2-candidate-import.reference-hmac-master-key=synthetic-only-master-key-material-that-is-long-enough",
        "ticket-defender.track2-candidate-import.reference-hmac-key-version=v1"
})
@EnabledIfEnvironmentVariable(named = "TD_ISSUE32_MYSQL_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:13307/td_issue32.*")
class Track1QuantityHttpMySqlTest {
    static final String ISOLATION_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
    /** 설정하면 합성 스텁 대신 격리 환경의 실제 Data v2를 호출합니다. 기본 CI에서는 비어 있습니다. */
    private static final String LIVE_URL = System.getenv("TD_ISSUE81_LIVE_FASTAPI_URL");
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final AtomicReference<Behavior> BEHAVIOR = new AtomicReference<>(Behavior.EVALUATE);
    private static final AtomicReference<String> LAST_BODY = new AtomicReference<>();
    private static HttpServer data;

    enum Behavior { EVALUATE, HTTP_500, BAD_CONTRACT, BLOCK }
    private static final AtomicReference<CountDownLatch> RELEASE = new AtomicReference<>();
    private static final AtomicReference<CountDownLatch> ENTERED = new AtomicReference<>();

    @LocalServerPort private int port;
    @Autowired private TenantRepository tenants;
    @Autowired private TenantEventRepository events;
    @Autowired private AdminMembershipRepository memberships;
    @Autowired private MembershipRoleRepository roles;
    @Autowired private MembershipEventGrantRepository grants;
    @Autowired private SyntheticReservationImportTenantRepository syntheticTenants;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean(name = "adminJwtDecoder") private JwtDecoder decoder;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeAll
    static void startData() throws IOException {
        data = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        data.setExecutor(Executors.newCachedThreadPool());
        data.createContext("/api/v2/clusters/analyze", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            LAST_BODY.set(body);
            Behavior behavior = BEHAVIOR.get();
            if (behavior == Behavior.BLOCK) {
                ENTERED.get().countDown();
                try { RELEASE.get().await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            byte[] out = behavior == Behavior.HTTP_500 ? "{}".getBytes(StandardCharsets.UTF_8) : respond(body, behavior).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(behavior == Behavior.HTTP_500 ? 500 : 200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        data.start();
    }

    @AfterAll
    static void stopData() { data.stop(0); }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TD_ISSUE32_MYSQL_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("TD_ISSUE32_MYSQL_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("TD_ISSUE32_MYSQL_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("FASTAPI_URL", () -> LIVE_URL != null ? LIVE_URL : "http://127.0.0.1:" + data.getAddress().getPort());
    }

    @BeforeEach
    void reset() {
        BEHAVIOR.set(Behavior.EVALUATE);
        when(decoder.decode(anyString())).thenAnswer(call -> Jwt.withTokenValue(call.getArgument(0)).header("alg", "RS256").subject(call.getArgument(0)).build());
    }

    @Test
    void multiSeatAndLimitScenariosKeepFindingsAndClustersSeparate() throws Exception {
        Fixture f = fixture();
        String performance = performance(f);
        policy(f, performance, "NETTED", "PERFORMANCE", "2026-01-01T00:00:00");
        // A는 같은 예매 3좌석으로 3매, B는 2매 한도 내, C는 주문 라인 2매와 취소 1매라 NETTED 후 2매입니다.
        String body = importBody(performance, "src-limit", true, List.of(
                row("acct-A", "res-A", "t-1", 1, "TICKET", "ACTIVE", "dev-shared"), row("acct-A", "res-A", "t-2", 1, "TICKET", "ACTIVE", "dev-shared"),
                row("acct-A", "res-A", "t-3", 1, "TICKET", "ACTIVE", "dev-shared"), row("acct-B", "res-B", "t-4", 2, "ORDER_LINE", "ACTIVE", "dev-shared"),
                row("acct-C", "res-C", "t-5", 2, "ORDER_LINE", "ACTIVE", "dev-c"), row("acct-C", "res-C", "t-6", 1, "TICKET", "CANCELLED", "dev-c")));
        String key = UUID.randomUUID().toString();
        HttpResponse<String> imported = post(f, "/ledger-imports", body, key, f.admin());
        assertThat(imported.statusCode()).isEqualTo(201);
        assertThat(imported.body()).contains("\"status\":\"ACCEPTED\"", "\"ledgerVersion\":1").doesNotContain("acct-A", "res-A", "t-1\"", "dev-shared", "Hmac");
        JsonNode batch = JSON.readTree(imported.body());
        List<Map<String, Object>> persistedReferences = jdbc.queryForList("select account_ref_hmac,reservation_ref_hmac,"
                + "track2_reservation_ref_hmac,ticket_ref_hmac,device_hmac from track1_purchase_ledger_rows where batch_id=?",
                batch.get("batchId").asText());
        assertThat(persistedReferences).hasSize(6);
        assertThat(persistedReferences.toString()).doesNotContain("acct-A", "res-A", "t-1", "dev-shared");
        assertThat(persistedReferences).allSatisfy(row -> {
            assertThat(row.get("track2_reservation_ref_hmac")).asString().matches("[0-9a-f]{64}");
            assertThat(row.get("reservation_ref_hmac")).asString().matches("[0-9a-f]{64}");
            assertThat(row.get("track2_reservation_ref_hmac")).isNotEqualTo(row.get("reservation_ref_hmac"));
        });
        assertThat(jdbc.queryForObject("select track2_link_digest from track1_purchase_ledger_batches where id=?",
                String.class, batch.get("batchId").asText())).matches("[0-9a-f]{64}");
        assertThat(post(f, "/ledger-imports", body, key, f.admin()).body()).contains(batch.get("batchId").asText());
        assertThat(post(f, "/ledger-imports", body.replace("res-B", "res-Z"), key, f.admin()).statusCode()).isEqualTo(409);

        JsonNode run = run(f, performance, batch.get("batchId").asText());
        assertThat(run.get("executionStatus").asText()).isEqualTo("SUCCEEDED");
        assertThat(run.at("/evaluation/status").asText()).isEqualTo("EVALUATED");
        assertThat(run.at("/evaluation/findings")).hasSize(1);
        assertThat(run.at("/evaluation/findings/0/effectiveTicketQuantity").asInt()).isEqualTo(3);
        assertThat(run.at("/evaluation/findings/0/maxTicketsPerAccount").asInt()).isEqualTo(2);
        assertThat(run.at("/evaluation/findings/0/reservationQuantities/0/effectiveTicketQuantity").asInt()).isEqualTo(3);
        assertThat(run.get("nettedExcludedRowCount").asInt()).isEqualTo(1);
        assertThat(run.get("clusterCount").asInt()).isEqualTo(1);
        assertThat(run.at("/clusters/0/memberCount").asInt()).isGreaterThan(0);
        if (LIVE_URL == null) assertThat(LAST_BODY.get()).doesNotContain("acct-A", "res-A", "dev-shared").doesNotContain("CANCELLED");
        assertThat(run.toString()).doesNotContain(hmacOfAny(run));
        assertThat(jdbc.queryForObject("select count(*) from macro_audit_evidence where tenant_id=? and analysis_request_id=?",
                Integer.class, f.tenant().toString(), run.get("analysisRequestId").asText())).isGreaterThan(0);
        HttpResponse<String> seatsImported = reservationImport(f, JSON.writeValueAsString(Map.of(
                "performanceId", performance, "sourceVersion", "synthetic-seat-ledger-v1", "completeSnapshot", true,
                "rows", List.of(
                        Map.of("reservationReference", "res-A", "ticketReference", "seat-ticket-12",
                                "zone", "19구역", "row", "8열", "seat", "12번"),
                        Map.of("reservationReference", "res-A", "ticketReference", "seat-ticket-13",
                                "zone", "19구역", "row", "8열", "seat", "13번")))),
                UUID.randomUUID().toString(), f.admin());
        assertThat(seatsImported.statusCode()).as(seatsImported.body()).isEqualTo(201);
        assertThat(seatsImported.body()).contains("\"status\":\"ACCEPTED\"");
        JsonNode detail = JSON.readTree(rawGet("/api/v1/admin/track1/clusters/detail?eventId=" + f.event() + "&analysisRequestId=" + run.get("analysisRequestId").asText()
                + "&clusterId=" + run.at("/clusters/0/clusterId").asText() + "&performanceId=" + performance
                + "&sourceVersion=synthetic-seat-ledger-v1", f.admin()).body());
        assertThat(detail.at("/verificationContext/status").asText()).isEqualTo("CURRENT_COMPLETE");
        assertThat(detail.at("/verificationContext/basis").asText()).isEqualTo("RESERVATION_REFERENCE");
        assertThat(detail.at("/members").toString()).contains("FOUND", "19구역", "8열", "12번", "13번")
                .doesNotContain("res-A", hmacOfAny(detail.at("/members")));
        assertThat(detail.at("/graph/edges").size()).isGreaterThan(1);
        assertThat(detail.at("/graph/nodes/0/kind").asText()).isEqualTo("signal");
        assertThat(detail.at("/graph").toString()).doesNotContain(hmacOfAny(detail.at("/graph")));
        HttpResponse<String> history = get(f, "/analysis-runs?page=0&pageSize=10", f.admin());
        assertThat(history.body()).contains("\"hasNext\":false", run.get("runId").asText());
    }

    @Test
    void zeroFindingsStayDistinctFromNonEvaluationAndFailures() throws Exception {
        Fixture f = fixture();
        String performance = performance(f);
        // 정책이 없으면 수량이 정상이어도 POLICY_NOT_CONFIRMED입니다.
        JsonNode noPolicy = runAfterImport(f, performance, "src-nopolicy", true, "ACTIVE");
        assertThat(noPolicy.at("/evaluation/status").asText()).isEqualTo("POLICY_NOT_CONFIRMED");
        assertThat(noPolicy.at("/evaluation/reasonCode").asText()).isEqualTo("NO_CONFIRMED_ACCOUNT_POLICY");
        assertThat(noPolicy.at("/evaluation/findings")).isEmpty();

        policy(f, performance, "NETTED", "PERFORMANCE", "2026-01-01T00:00:00");
        JsonNode unknown = runAfterImport(f, performance, "src-unknown", true, "UNKNOWN");
        assertThat(unknown.at("/evaluation/status").asText()).isEqualTo("QUANTITY_DATA_INCOMPLETE");
        JsonNode partial = runAfterImport(f, performance, "src-partial", false, "ACTIVE");
        assertThat(partial.at("/evaluation/status").asText()).isEqualTo("QUANTITY_DATA_INCOMPLETE");
        assertThat(partial.at("/evaluation/reasonCode").asText()).isEqualTo("INCOMPLETE_SNAPSHOT");
        JsonNode clean = runAfterImport(f, performance, "src-clean", true, "ACTIVE");
        assertThat(clean.at("/evaluation/status").asText()).isEqualTo("EVALUATED");
        assertThat(clean.at("/evaluation/findings")).isEmpty();

        BEHAVIOR.set(Behavior.HTTP_500);
        JsonNode down = runAfterImport(f, performance, "src-down", true, "ACTIVE");
        assertThat(down.get("executionStatus").asText()).isEqualTo("FAILED");
        assertThat(down.get("failureCode").asText()).isEqualTo("ANALYZER_HTTP_5XX");
        assertThat(down.get("retryable").asBoolean()).isTrue();
        assertThat(down.get("evaluation").isNull()).isTrue();

        BEHAVIOR.set(Behavior.BAD_CONTRACT);
        JsonNode bad = run(f, performance, down.get("ledgerBatchId").asText());
        assertThat(bad.get("failureCode").asText()).isEqualTo("ANALYZER_CONTRACT_VIOLATION");
        assertThat(bad.get("retryable").asBoolean()).isFalse();
        BEHAVIOR.set(Behavior.EVALUATE);
        assertThat(run(f, performance, down.get("ledgerBatchId").asText()).get("executionStatus").asText()).isEqualTo("SUCCEEDED");
    }

    @Test
    void policyWindowGrossAndEventScopeAreNotEvaluated() throws Exception {
        Fixture f = fixture();
        String p1 = performance(f);
        policy(f, p1, "NETTED", "PERFORMANCE", "2027-01-01T00:00:00");
        assertThat(runAfterImport(f, p1, "src-time", true, "ACTIVE").at("/evaluation/status").asText()).isEqualTo("POLICY_TIME_NOT_APPLICABLE");
        String p2 = performance(f);
        policy(f, p2, "GROSS", "PERFORMANCE", "2026-01-01T00:00:00");
        JsonNode gross = runAfterImport(f, p2, "src-gross", true, "ACTIVE");
        assertThat(gross.at("/evaluation/status").asText()).isEqualTo("QUANTITY_DATA_INCOMPLETE");
        assertThat(gross.at("/evaluation/reasonCode").asText()).isEqualTo("GROSS_RULE_NOT_SUPPORTED");
        String p3 = performance(f);
        policy(f, p3, "NETTED", "EVENT", "2026-01-01T00:00:00");
        JsonNode scoped = runAfterImport(f, p3, "src-event", true, "ACTIVE");
        assertThat(scoped.at("/evaluation/status").asText()).isEqualTo("POLICY_NOT_CONFIRMED");
        assertThat(scoped.at("/evaluation/reasonCode").asText()).isEqualTo("EVENT_SCOPE_AGGREGATION_NOT_SUPPORTED");
    }

    @Test
    void invalidInputIsAuditedAndNeverMadeUp() throws Exception {
        Fixture f = fixture();
        String performance = performance(f);
        Map<String, Object> noTime = new HashMap<>(row("a", "r", "t", 1, "TICKET", "ACTIVE", "d"));
        noTime.remove("purchasedAt");
        List<Map<String, Object>> rows = List.of(noTime, row("a2", "r2", "t2", 2, "TICKET", "ACTIVE", "d"), row("a3", "r3", "t3", 1, "TICKET", "ACTIVE", null),
                row("a4", "r4", "t4", 1, "TICKET", "ACTIVE", "d"), row("a5", "r5", "t4", 1, "TICKET", "ACTIVE", "d"), row("a6", "r4", "t6", 1, "TICKET", "ACTIVE", "d"));
        HttpResponse<String> rejected = post(f, "/ledger-imports", importBody(performance, "src-bad", true, rows), UUID.randomUUID().toString(), f.admin());
        assertThat(rejected.statusCode()).isEqualTo(201);
        assertThat(rejected.body()).contains("\"status\":\"REJECTED\"", "PURCHASED_AT_REQUIRED", "TICKET_UNIT_QUANTITY_MUST_BE_ONE",
                "RELATIONSHIP_SIGNAL_REQUIRED", "DUPLICATE_TICKET_REFERENCE", "RESERVATION_ACCOUNT_CONFLICT");
        JsonNode batch = JSON.readTree(rejected.body());
        assertThat(post(f, "/analysis-runs", runBody(performance, batch.get("batchId").asText()), UUID.randomUUID().toString(), f.admin()).statusCode()).isEqualTo(409);
        // 거절된 배치는 같은 sourceVersion의 교정 재반입을 막지 않습니다.
        assertThat(post(f, "/ledger-imports", importBody(performance, "src-bad", true, List.of(row("a", "r", "t", 1, "TICKET", "ACTIVE", "d"))), UUID.randomUUID().toString(), f.admin()).body())
                .contains("\"status\":\"ACCEPTED\"", "\"ledgerVersion\":2");
        assertThat(post(f, "/ledger-imports", importBody(performance, "src-bad", true, List.of(row("x", "y", "z", 1, "TICKET", "ACTIVE", "d"))), UUID.randomUUID().toString(), f.admin()).statusCode()).isEqualTo(409);
        String snapshot = importBody(performance, "src-snap", true, List.of()).replace("INDIVIDUAL_TICKET", "ACCOUNT_LIMIT_SNAPSHOT");
        assertThat(post(f, "/ledger-imports", snapshot, UUID.randomUUID().toString(), f.admin()).body()).contains("REJECTED", "ACCOUNT_SNAPSHOT_NOT_SUPPORTED_BY_DATA_V2");
        assertThat(get(f, "/ledger-imports", f.viewer()).body()).contains("src-snap", "PURCHASED_AT_REQUIRED");
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 1001; i++) many.add(row("a", "r", "tick-" + i, 1, "TICKET", "ACTIVE", "d"));
        assertThat(post(f, "/ledger-imports", importBody(performance, "src-many", true, many), UUID.randomUUID().toString(), f.admin()).statusCode()).isEqualTo(422);
        String huge = importBody(performance, "src-huge", true, List.of(row("a".repeat(2_100_000), "r", "t", 1, "TICKET", "ACTIVE", "d")));
        assertThat(post(f, "/ledger-imports", huge, UUID.randomUUID().toString(), f.admin()).statusCode()).isEqualTo(413);
    }

    @Test
    void roleAndTenantScopeAreEnforced() throws Exception {
        Fixture f = fixture();
        Fixture other = fixture();
        String performance = performance(f);
        String body = importBody(performance, "src-scope", true, List.of(row("a", "r", "t", 1, "TICKET", "ACTIVE", "d")));
        assertThat(post(f, "/ledger-imports", body, UUID.randomUUID().toString(), f.viewer()).statusCode()).isEqualTo(403);
        assertThat(post(f, "/ledger-imports", body, UUID.randomUUID().toString(), other.admin()).statusCode()).isIn(403, 404);
        JsonNode batch = JSON.readTree(post(f, "/ledger-imports", body, UUID.randomUUID().toString(), f.admin()).body());
        String batchId = batch.get("batchId").asText();
        assertThat(post(f, "/analysis-runs", runBody(performance, batchId), UUID.randomUUID().toString(), f.viewer()).statusCode()).isEqualTo(403);
        // 다른 고객사가 자신의 공연 경로로 같은 배치와 회차를 요청해도 존재 여부를 알 수 없습니다.
        assertThat(post(other, "/analysis-runs", runBody(performance, batchId), UUID.randomUUID().toString(), other.admin()).statusCode()).isEqualTo(404);
        JsonNode run = run(f, performance, batchId);
        assertThat(get(other, "/analysis-runs/" + run.get("runId").asText(), other.admin()).statusCode()).isEqualTo(404);
        assertThat(get(f, "/analysis-runs/" + run.get("runId").asText(), f.viewer()).statusCode()).isEqualTo(200);
        assertThat(get(other, "/analysis-runs", other.admin()).body()).doesNotContain(run.get("runId").asText());
        assertThat(client.send(HttpRequest.newBuilder(URI.create(base(f) + "/analysis-runs")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }

    @Test
    void concurrentRunOnSameBatchIsRefusedWithoutHoldingDatabaseLocks() throws Exception {
        Fixture f = fixture();
        String performance = performance(f);
        policy(f, performance, "NETTED", "PERFORMANCE", "2026-01-01T00:00:00");
        String batchId = JSON.readTree(post(f, "/ledger-imports", importBody(performance, "src-concurrent", true, List.of(row("a", "r", "t", 1, "TICKET", "ACTIVE", "d"))), UUID.randomUUID().toString(), f.admin()).body()).get("batchId").asText();
        ENTERED.set(new CountDownLatch(1));
        RELEASE.set(new CountDownLatch(1));
        BEHAVIOR.set(Behavior.BLOCK);
        var pool = Executors.newSingleThreadExecutor();
        Future<HttpResponse<String>> first = pool.submit(() -> post(f, "/analysis-runs", runBody(performance, batchId), UUID.randomUUID().toString(), f.admin()));
        assertThat(ENTERED.get().await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        // 외부 호출 중에도 회차 잠금이 없어 빠르게 409를 받고 조회도 가능합니다.
        HttpResponse<String> second = post(f, "/analysis-runs", runBody(performance, batchId), UUID.randomUUID().toString(), f.admin());
        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(second.body()).contains("TRACK1_QUANTITY_RUN_IN_PROGRESS");
        assertThat(get(f, "/analysis-runs", f.admin()).body()).contains("\"executionStatus\":\"RUNNING\"");
        RELEASE.get().countDown();
        assertThat(first.get().statusCode()).isEqualTo(202);
        assertThat(first.get().body()).contains("\"executionStatus\":\"SUCCEEDED\"");
        pool.shutdown();
    }

    @Test
    void consoleQueriesReturnOnlyStoredRowsWithinScope() throws Exception {
        Fixture f = fixture();
        Fixture other = fixture();
        String performance = performance(f);
        runAfterImport(f, performance, "src-console", true, "ACTIVE");
        assertThat(rawGet("/api/v1/admin/me", f.admin()).body()).contains(f.tenant().toString(), f.event().toString(), "TENANT_ADMIN");
        assertThat(rawGet("/api/v1/admin/me", "unknown-subject").statusCode()).isEqualTo(404);
        String event = "/api/v1/admin/events/" + f.event();
        HttpResponse<String> summary = rawGet(event + "/workflow-summary", f.viewer());
        assertThat(summary.statusCode()).isEqualTo(200);
        assertThat(summary.body()).contains("\"SUCCEEDED\":1", "PERSISTED_ROWS_ONLY");
        assertThat(rawGet(event + "/approval-requests?status=PENDING", f.viewer()).body()).contains("\"items\":[]");
        assertThat(rawGet(event + "/approval-requests?status=NOPE", f.viewer()).statusCode()).isEqualTo(422);
        assertThat(rawGet(event + "/approval-audit-events", f.viewer()).statusCode()).isEqualTo(200);
        assertThat(rawGet(event + "/reservation-imports", f.viewer()).body()).contains("\"items\":[]");
        for (String path : List.of("/workflow-summary", "/approval-requests", "/approval-audit-events", "/reservation-imports"))
            assertThat(rawGet(event + path, other.admin()).statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> rawGet(String path, String subject) throws Exception {
        return send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET(), subject, null);
    }

    private JsonNode runAfterImport(Fixture f, String performance, String source, boolean complete, String status) throws Exception {
        HttpResponse<String> imported = post(f, "/ledger-imports", importBody(performance, source, complete, List.of(row("a", "r", "t", 1, "TICKET", status, "d"))), UUID.randomUUID().toString(), f.admin());
        assertThat(imported.statusCode()).isEqualTo(201);
        return run(f, performance, JSON.readTree(imported.body()).get("batchId").asText());
    }

    private JsonNode run(Fixture f, String performance, String batch) throws Exception {
        HttpResponse<String> response = post(f, "/analysis-runs", runBody(performance, batch), UUID.randomUUID().toString(), f.admin());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        return JSON.readTree(response.body());
    }

    private static String runBody(String performance, String batch) { return "{\"performanceId\":\"" + performance + "\",\"ledgerBatchId\":\"" + batch + "\"}"; }

    private String performance(Fixture f) throws Exception {
        HttpResponse<String> created = send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/admin/events/" + f.event() + "/verified-performances"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"performanceRef\":\"round-" + UUID.randomUUID() + "\",\"verificationBasis\":\"synthetic confirmed\",\"sourceVersion\":\"p-v1\"}")), f.admin(), null);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return JSON.readTree(created.body()).get("performanceId").asText();
    }

    private void policy(Fixture f, String performance, String rule, String scope, String from) throws Exception {
        String body = JSON.writeValueAsString(Map.of("performanceId", performance, "maxTicketsPerAccount", 2, "policyVersion", "policy-" + UUID.randomUUID(), "effectiveFrom", from,
                "policySource", "synthetic", "verificationBasis", "synthetic confirmed", "limitSubject", "ACCOUNT", "applicationScope", scope, "cancellationRefundRule", rule, "includesPresale", true));
        HttpResponse<String> created = send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/admin/events/" + f.event() + "/purchase-limit-policies")).POST(HttpRequest.BodyPublishers.ofString(body)), f.admin(), null);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
    }

    private static Map<String, Object> row(String account, String reservation, String ticket, int quantity, String unit, String status, String device) {
        Map<String, Object> row = new HashMap<>(Map.of("accountReference", account, "reservationReference", reservation, "ticketReference", ticket,
                "purchasedAt", "2026-11-02T10:00:00Z", "quantity", quantity, "quantityUnit", unit, "ticketStatus", status));
        if (device != null) row.put("deviceReference", device);
        return row;
    }

    private static String importBody(String performance, String source, boolean complete, List<Map<String, Object>> rows) throws Exception {
        return JSON.writeValueAsString(Map.of("performanceId", performance, "inputType", "INDIVIDUAL_TICKET", "sourceVersion", source, "sourceAsOf", "2026-11-08T10:00:00Z",
                "quantityBasis", "PROVIDER_TICKET_LEDGER", "completeSnapshot", complete, "rows", rows));
    }

    private String base(Fixture f) { return "http://127.0.0.1:" + port + "/api/v1/admin/events/" + f.event() + "/track1-quantity"; }

    private HttpResponse<String> post(Fixture f, String path, String body, String key, String subject) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base(f) + path)).POST(HttpRequest.BodyPublishers.ofString(body)), subject, key);
    }

    private HttpResponse<String> get(Fixture f, String path, String subject) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base(f) + path)).GET(), subject, null);
    }

    private HttpResponse<String> reservationImport(Fixture f, String body, String key, String subject) throws Exception {
        return send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/admin/events/" + f.event()
                + "/reservation-imports")).POST(HttpRequest.BodyPublishers.ofString(body)), subject, key);
    }

    private HttpResponse<String> send(HttpRequest.Builder request, String subject, String key) throws Exception {
        request.header("Authorization", "Bearer " + subject).header("Content-Type", "application/json").header("Accept", "application/json");
        if (key != null) request.header("Idempotency-Key", key);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 응답 어디에도 64자리 저장 HMAC이 없어야 합니다. 있으면 그 값을 돌려 단언이 실패합니다. */
    private static String hmacOfAny(JsonNode run) {
        var matcher = java.util.regex.Pattern.compile("(?<!cluster-)[0-9a-f]{64}").matcher(run.toString());
        return matcher.find() ? matcher.group() : "no-stored-hmac-present";
    }

    private Fixture fixture() {
        UUID tenant = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        String admin = member(tenant, event, "admin", List.of(AdminRole.TENANT_ADMIN, AdminRole.ANALYST), true);
        tenants.count();
        String viewer = member(tenant, event, "viewer", List.of(AdminRole.REVIEWER), false);
        return new Fixture(tenant, event, admin, viewer);
    }

    private String member(UUID tenant, UUID event, String prefix, List<AdminRole> memberRoles, boolean create) {
        if (create) {
            tenants.saveAndFlush(new Tenant(tenant, "Synthetic quantity tenant", AccessRecordStatus.ACTIVE));
            events.saveAndFlush(new TenantEvent(event, tenant, "event-" + event, "Synthetic quantity event", AccessRecordStatus.ACTIVE));
            syntheticTenants.saveAndFlush(new SyntheticReservationImportTenant(tenant, UUID.fromString(ISOLATION_ID), LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1)));
        }
        UUID id = UUID.randomUUID();
        String subject = prefix + "-" + id;
        memberships.saveAndFlush(new AdminMembershipEntity(id, tenant, subject, AccessRecordStatus.ACTIVE));
        memberRoles.forEach(role -> roles.saveAndFlush(new MembershipRole(UUID.randomUUID(), id, role)));
        grants.saveAndFlush(new MembershipEventGrant(UUID.randomUUID(), id, tenant, event));
        return subject;
    }

    /** Data v2 계약 모양만 따르는 합성 응답입니다. 2명 이상이 기기 토큰을 공유하면 군집 1개를 돌려줍니다. */
    private static String respond(String body, Behavior behavior) throws IOException {
        JsonNode request = JSON.readTree(body);
        JsonNode scope = request.get("performanceScopes").get(0);
        Map<String, Integer> totals = new HashMap<>();
        Map<String, Map<String, Integer>> perReservation = new HashMap<>();
        Map<String, List<JsonNode>> byDevice = new HashMap<>();
        boolean complete = "COMPLETE".equals(scope.get("quantityDataStatus").asText());
        boolean allActive = true;
        boolean inWindow = true;
        for (JsonNode ticket : request.get("tickets")) {
            allActive &= "ACTIVE".equals(ticket.get("ticketStatus").asText());
            if (scope.hasNonNull("policy")) inWindow &= ticket.get("purchasedAt").asText().compareTo(scope.at("/policy/effectiveFrom").asText()) >= 0;
            totals.merge(ticket.get("accountId").asText(), ticket.get("effectiveTicketQuantity").asInt(), Integer::sum);
            perReservation.computeIfAbsent(ticket.get("accountId").asText(), ignored -> new HashMap<>()).merge(ticket.get("reservationNo").asText(), ticket.get("effectiveTicketQuantity").asInt(), Integer::sum);
            byDevice.computeIfAbsent(ticket.get("deviceIdHash").asText(), ignored -> new ArrayList<>()).add(ticket);
        }
        String status = !scope.hasNonNull("policy") ? "POLICY_NOT_CONFIRMED" : !complete || !allActive ? "QUANTITY_DATA_INCOMPLETE" : !inWindow ? "POLICY_TIME_NOT_APPLICABLE" : "EVALUATED";
        if (behavior == Behavior.BAD_CONTRACT && scope.hasNonNull("policy")) status = "POLICY_NOT_CONFIRMED";
        List<Map<String, Object>> findings = new ArrayList<>();
        if ("EVALUATED".equals(status)) {
            int max = scope.at("/policy/maxTicketsPerAccount").asInt();
            totals.forEach((account, quantity) -> {
                if (quantity > max) findings.add(Map.of("accountId", account, "effectiveTicketQuantity", quantity, "maxTicketsPerAccount", max, "policyVersion", scope.at("/policy/policyVersion").asText(),
                        "reservationQuantities", perReservation.get(account).entrySet().stream().map(e -> Map.of("reservationNo", e.getKey(), "effectiveTicketQuantity", e.getValue())).toList()));
            });
        }
        List<Map<String, Object>> clusters = new ArrayList<>();
        byDevice.forEach((device, tickets) -> {
            Map<String, Map<String, String>> members = new java.util.LinkedHashMap<>();
            tickets.forEach(t -> members.putIfAbsent(t.get("accountId").asText() + t.get("reservationNo").asText(), Map.of("accountId", t.get("accountId").asText(), "reservationNo", t.get("reservationNo").asText(), "eventId", t.get("eventId").asText())));
            if (members.size() >= 2) clusters.add(Map.of("clusterId", "c-" + clusters.size(), "members", List.copyOf(members.values()), "paymentHashes", List.of(), "addressHashes", List.of(), "deviceIdHashes", List.of(device), "ipHashes", List.of(), "riskScore", 0.4));
        });
        Map<String, Object> evaluation = new HashMap<>(Map.of("internalEventId", scope.get("internalEventId").asText(), "eventId", scope.get("eventId").asText(), "performanceId", scope.get("performanceId").asText(), "status", status, "accountLimitFindings", findings));
        evaluation.put("policyVersion", scope.hasNonNull("policy") ? scope.at("/policy/policyVersion").asText() : null);
        return JSON.writeValueAsString(Map.of("requestId", request.get("requestId").asText(), "status", "COMPLETED", "algorithm", "LOUVAIN", "algorithmVersion", "synthetic-stub-not-quality", "analyzedAt", "2026-11-09T00:00:00Z",
                "clusters", clusters, "purchaseLimitEvaluations", List.of(evaluation)));
    }

    private record Fixture(UUID tenant, UUID event, String admin, String viewer) { }
}
