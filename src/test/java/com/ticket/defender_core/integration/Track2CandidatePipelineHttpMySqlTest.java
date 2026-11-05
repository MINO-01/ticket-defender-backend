package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.out.persistence.AdminMembershipRepository;
import com.ticket.defender_core.adapter.out.persistence.EvidenceAssetRepository;
import com.ticket.defender_core.adapter.out.persistence.FanReportRepository;
import com.ticket.defender_core.adapter.out.persistence.ListingContextAnalysisRepository;
import com.ticket.defender_core.adapter.out.persistence.ListingContextObservationRepository;
import com.ticket.defender_core.adapter.out.persistence.MembershipEventGrantRepository;
import com.ticket.defender_core.adapter.out.persistence.MembershipRoleRepository;
import com.ticket.defender_core.adapter.out.persistence.SyntheticReservationImportTenantRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantEventRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantRepository;
import com.ticket.defender_core.domain.AccessRecordStatus;
import com.ticket.defender_core.domain.AdminMembershipEntity;
import com.ticket.defender_core.domain.EvidenceAsset;
import com.ticket.defender_core.domain.FanReport;
import com.ticket.defender_core.domain.ListingContextAnalysis;
import com.ticket.defender_core.domain.ListingContextObservation;
import com.ticket.defender_core.domain.MembershipEventGrant;
import com.ticket.defender_core.domain.MembershipRole;
import com.ticket.defender_core.domain.SyntheticReservationImportTenant;
import com.ticket.defender_core.domain.Tenant;
import com.ticket.defender_core.domain.TenantEvent;
import com.ticket.defender_core.security.AdminRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "ticket-defender.track2-candidate-import.enabled=true",
        "ticket-defender.track2-candidate-import.operational-mode=SYNTHETIC_ISOLATED",
        "ticket-defender.track2-candidate-import.synthetic-isolation-id=" + Track2CandidatePipelineHttpMySqlTest.ISOLATION_ID,
        "ticket-defender.track2-candidate-import.reference-hmac-master-key=synthetic-only-master-key-material-that-is-long-enough",
        "ticket-defender.track2-candidate-import.reference-hmac-key-version=v1"
})
@EnabledIfEnvironmentVariable(named = "TD_ISSUE32_MYSQL_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:13307/td_issue32.*")
class Track2CandidatePipelineHttpMySqlTest {
    static final String ISOLATION_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    private static final Pattern PERFORMANCE_ID = Pattern.compile("\\\"performanceId\\\":\\\"([^\\\"]+)\\\"");

    @LocalServerPort private int port;
    @Autowired private TenantRepository tenants;
    @Autowired private TenantEventRepository events;
    @Autowired private AdminMembershipRepository memberships;
    @Autowired private MembershipRoleRepository roles;
    @Autowired private MembershipEventGrantRepository grants;
    @Autowired private FanReportRepository reports;
    @Autowired private EvidenceAssetRepository assets;
    @Autowired private ListingContextAnalysisRepository analyses;
    @Autowired private ListingContextObservationRepository observations;
    @Autowired private SyntheticReservationImportTenantRepository syntheticTenants;
    @MockitoBean(name = "adminJwtDecoder") private JwtDecoder decoder;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TD_ISSUE32_MYSQL_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("TD_ISSUE32_MYSQL_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("TD_ISSUE32_MYSQL_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Test
    void isolatedMysqlHttpFlowCreatesOnlyOpaqueCandidateResult() throws Exception {
        Fixture fixture = fixture();
        when(decoder.decode(anyString())).thenAnswer(call -> Jwt.withTokenValue(call.getArgument(0))
                .header("alg", "RS256").subject(fixture.subject()).build());
        HttpClient client = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + port;

        HttpResponse<String> performance = send(client, base + "/api/v1/admin/events/" + fixture.eventId()
                + "/verified-performances", "POST", "{\"performanceRef\":\"round-http-1\","
                + "\"verificationBasis\":\"synthetic confirmed\",\"sourceVersion\":\"performance-v1\"}",
                fixture.subject(), null);
        assertThat(performance.statusCode()).isEqualTo(201);
        String performanceId = id(performance.body());

        HttpResponse<String> link = send(client, base + "/api/v1/admin/fan-reports/" + fixture.reportId()
                + "/performance-link", "PUT", "{\"performanceId\":\"" + performanceId
                + "\",\"expectedLinkRevision\":0,\"reason\":\"synthetic reviewer link\"}", fixture.subject(),
                UUID.randomUUID().toString());
        assertThat(link.statusCode()).isEqualTo(201);

        HttpResponse<String> imported = send(client, base + "/api/v1/admin/events/" + fixture.eventId()
                + "/reservation-imports", "POST", "{\"performanceId\":\"" + performanceId
                + "\",\"sourceVersion\":\"reservation-v1\",\"completeSnapshot\":true,\"rows\":[{"
                + "\"reservationReference\":\"synthetic-reservation-private\",\"zone\":\"VIP\","
                + "\"row\":\"10\",\"seat\":\"5\"}]}", fixture.subject(), UUID.randomUUID().toString());
        assertThat(imported.statusCode()).isEqualTo(201);
        assertThat(imported.body()).contains("\"status\":\"ACCEPTED\"")
                .doesNotContain("synthetic-reservation-private", "reservationRefHmac", "accountId");

        HttpResponse<String> candidate = send(client, base + "/api/v1/admin/fan-reports/" + fixture.reportId()
                + "/evidence/" + fixture.evidenceId() + "/listing-analyses/" + fixture.analysisId()
                + "/candidate-matches", "POST", null, fixture.subject(), UUID.randomUUID().toString());
        assertThat(candidate.statusCode()).isEqualTo(201);
        assertThat(candidate.body()).contains("\"status\":\"MATCH_CANDIDATE\"", "\"applicability\":\"CURRENT\"")
                .doesNotContain("synthetic-reservation-private", "accountId", "reservationRefHmac", "mappingScore");
    }

    private Fixture fixture() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        String subject = "candidate-http-" + member;
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        tenants.saveAndFlush(new Tenant(tenant, "Synthetic candidate tenant", AccessRecordStatus.ACTIVE));
        events.saveAndFlush(new TenantEvent(event, tenant, "event-" + event, "Synthetic candidate event",
                AccessRecordStatus.ACTIVE));
        memberships.saveAndFlush(new AdminMembershipEntity(member, tenant, subject, AccessRecordStatus.ACTIVE));
        for (AdminRole role : List.of(AdminRole.TENANT_ADMIN, AdminRole.REVIEWER, AdminRole.ANALYST))
            roles.saveAndFlush(new MembershipRole(UUID.randomUUID(), member, role));
        grants.saveAndFlush(new MembershipEventGrant(UUID.randomUUID(), member, tenant, event));
        syntheticTenants.saveAndFlush(new SyntheticReservationImportTenant(tenant, UUID.fromString(ISOLATION_ID),
                now.minusMinutes(1)));
        UUID reportId = UUID.randomUUID();
        String receiptNumber = "FR-HTTP-" + reportId.toString().replace("-", "");
        assertThat(receiptNumber).hasSize(40);
        FanReport report = new FanReport(reportId, receiptNumber, tenant, event, now.plusDays(1), null,
                FanReport.ReportType.PREMIUM_RESALE, "Synthetic listing report", null, null, null, null, now,
                UUID.randomUUID().toString(), "a".repeat(64), "b".repeat(64));
        report.markReceived();
        reports.saveAndFlush(report);
        UUID evidenceId = UUID.randomUUID();
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new byte[] {1, 2, 3, 4}));
        EvidenceAsset asset = new EvidenceAsset(evidenceId, reportId, "synthetic/" + evidenceId, "image/png", 4,
                sha, now, now.plusDays(90));
        asset.markStored("etag-http");
        assets.saveAndFlush(asset);
        UUID analysisId = UUID.randomUUID();
        ListingContextAnalysis analysis = new ListingContextAnalysis(analysisId, tenant, event, reportId, evidenceId,
                member, "c".repeat(64), UUID.randomUUID(), "d".repeat(64), "etag-http", sha, 4, "image/png", now,
                now.plusMinutes(5));
        analysis.recordVerifiedInput(sha, 4);
        analysis.succeed(ListingContextAnalysis.AnalysisStatus.COMPLETED, "synthetic", "v1", now,
                "e".repeat(64), "MATCH_SCORE_UNAVAILABLE", now);
        analyses.saveAndFlush(analysis);
        for (var field : List.of(ListingContextObservation.Field.zone, ListingContextObservation.Field.row,
                ListingContextObservation.Field.seat)) {
            String value = switch (field) {
                case zone -> "VIP";
                case row -> "10";
                case seat -> "5";
                default -> throw new IllegalStateException();
            };
            observations.saveAndFlush(new ListingContextObservation(analysisId, field,
                    ListingContextObservation.State.FOUND, value, null, null, value, null, List.of()));
        }
        return new Fixture(tenant, event, reportId, evidenceId, analysisId, subject);
    }

    private static HttpResponse<String> send(HttpClient client, String url, String method, String body, String subject,
            String idempotencyKey) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + subject)
                .header("Accept", "application/json");
        if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey);
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(switch (method) {
            case "POST" -> request.POST(body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body)).build();
            case "PUT" -> request.PUT(HttpRequest.BodyPublishers.ofString(body)).build();
            default -> throw new IllegalArgumentException(method);
        }, HttpResponse.BodyHandlers.ofString());
    }

    private static String id(String body) {
        Matcher matcher = PERFORMANCE_ID.matcher(body);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private record Fixture(UUID tenantId, UUID eventId, UUID reportId, UUID evidenceId, UUID analysisId, String subject) { }
}
