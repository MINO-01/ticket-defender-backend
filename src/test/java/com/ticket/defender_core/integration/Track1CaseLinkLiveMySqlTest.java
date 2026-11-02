package com.ticket.defender_core.integration;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.ticket.defender_core.adapter.in.web.dto.AgentAnalysisRequest;
import com.ticket.defender_core.adapter.in.web.dto.CaseDto;
import com.ticket.defender_core.adapter.out.persistence.AdminMembershipRepository;
import com.ticket.defender_core.adapter.out.persistence.CaseActivityRepository;
import com.ticket.defender_core.adapter.out.persistence.CaseIdempotencyRepository;
import com.ticket.defender_core.adapter.out.persistence.CaseRepository;
import com.ticket.defender_core.adapter.out.persistence.CaseSourceReferenceRepository;
import com.ticket.defender_core.adapter.out.persistence.MacroAuditEvidenceRepository;
import com.ticket.defender_core.adapter.out.persistence.MembershipEventGrantRepository;
import com.ticket.defender_core.adapter.out.persistence.MembershipRoleRepository;
import com.ticket.defender_core.adapter.out.persistence.ServiceTenantBindingRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantEventRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantRepository;
import com.ticket.defender_core.application.service.FraudAnalysisService;
import com.ticket.defender_core.application.service.FraudAnalysisStatus;
import com.ticket.defender_core.domain.AccessRecordStatus;
import com.ticket.defender_core.domain.AdminMembershipEntity;
import com.ticket.defender_core.domain.Case;
import com.ticket.defender_core.domain.CaseSourceReference;
import com.ticket.defender_core.domain.MacroAuditEvidence;
import com.ticket.defender_core.domain.MembershipEventGrant;
import com.ticket.defender_core.domain.MembershipRole;
import com.ticket.defender_core.domain.ServiceTenantBinding;
import com.ticket.defender_core.domain.Tenant;
import com.ticket.defender_core.domain.TenantEvent;
import com.ticket.defender_core.security.AdminRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실제 FastAPI와 Neo4j GDS Louvain 분석 결과를 격리 MySQL 8에 저장한 뒤 Case 출처로 연결하는 흐름을 검증합니다.
 * Spring 엔드포인트는 MockMvc로 호출하고, Spring에서 FastAPI로 가는 요청은 실제 loopback HTTP 호출입니다.
 * 두 환경 변수가 모두 없으면 실행되지 않으며, 실행 스크립트는 Skip을 실패로 처리합니다.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.show-sql=false"
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "TD_TRACK1_FASTAPI_URL", matches = "http://127\\.0\\.0\\.1:[1-9][0-9]*")
@EnabledIfEnvironmentVariable(named = "TD_MYSQL_MIGRATION_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:[1-9][0-9]*/.*")
class Track1CaseLinkLiveMySqlTest {
    private static final String ADMIN_ISSUER = "https://issuer.example.test/admin";
    private static final String SERVICE_ISSUER = "https://issuer.example.test/service";
    private static final String SERVICE_CLIENT = "trusted-ingest";
    private static final String UUID_PATTERN =
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";
    private static KeyPair keyPair;

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private FraudAnalysisService analysis;
    @Autowired private TenantRepository tenants;
    @Autowired private TenantEventRepository events;
    @Autowired private ServiceTenantBindingRepository bindings;
    @Autowired private AdminMembershipRepository memberships;
    @Autowired private MembershipRoleRepository roles;
    @Autowired private MembershipEventGrantRepository grants;
    @Autowired private MacroAuditEvidenceRepository evidence;
    @Autowired private CaseRepository cases;
    @Autowired private CaseSourceReferenceRepository sources;
    @Autowired private CaseActivityRepository activities;
    @Autowired private CaseIdempotencyRepository idempotency;

    @MockitoBean(name = "adminJwtDecoder") private JwtDecoder adminDecoder;
    @MockitoBean(name = "analysisJwtDecoder") private JwtDecoder analysisDecoder;

    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();
    private final UUID eventA = UUID.randomUUID();
    private final UUID eventB = UUID.randomUUID();
    private final String run = UUID.randomUUID().toString().substring(0, 8);
    // 두 고객사가 같은 외부 공연 ID를 써도 내부 공연과 고객사 범위로 분리되는지 확인합니다.
    private final String externalEvent = "e2e-show-" + run;
    private final String ingestA = "ingest-a-" + run;
    private final String ingestB = "ingest-b-" + run;
    private final String analystA = "analyst-a-" + run;
    private final String analystB = "analyst-b-" + run;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TD_MYSQL_MIGRATION_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("TD_MYSQL_MIGRATION_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("TD_MYSQL_MIGRATION_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @BeforeAll
    static void createKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
    }

    @BeforeEach
    void seed() {
        // 서명, 만료, 발급자는 실제 Nimbus 디코더로 검사합니다. 관리자와 내부 서비스는 기대 발급자가 다르므로 디코더를 나눕니다.
        // audience와 client_id 검사는 이 테스트 범위가 아니며 기존 인증 API 테스트가 담당합니다.
        NimbusJwtDecoder admin = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keyPair.getPublic()).build();
        admin.setJwtValidator(JwtValidators.createDefaultWithIssuer(ADMIN_ISSUER));
        NimbusJwtDecoder service = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keyPair.getPublic()).build();
        service.setJwtValidator(JwtValidators.createDefaultWithIssuer(SERVICE_ISSUER));
        given(adminDecoder.decode(anyString())).willAnswer(call -> admin.decode(call.getArgument(0)));
        given(analysisDecoder.decode(anyString())).willAnswer(call -> service.decode(call.getArgument(0)));

        tenants.saveAndFlush(new Tenant(tenantA, "E2E 합성 고객사 A", AccessRecordStatus.ACTIVE));
        tenants.saveAndFlush(new Tenant(tenantB, "E2E 합성 고객사 B", AccessRecordStatus.ACTIVE));
        events.saveAndFlush(new TenantEvent(eventA, tenantA, externalEvent, "합성 공연 A", AccessRecordStatus.ACTIVE));
        events.saveAndFlush(new TenantEvent(eventB, tenantB, externalEvent, "합성 공연 B", AccessRecordStatus.ACTIVE));
        bindings.saveAndFlush(new ServiceTenantBinding(UUID.randomUUID(), SERVICE_ISSUER, SERVICE_CLIENT,
                ingestA, tenantA, AccessRecordStatus.ACTIVE));
        bindings.saveAndFlush(new ServiceTenantBinding(UUID.randomUUID(), SERVICE_ISSUER, SERVICE_CLIENT,
                ingestB, tenantB, AccessRecordStatus.ACTIVE));
        analyst(analystA, tenantA, eventA);
        analyst(analystB, tenantB, eventB);
    }

    @AfterEach
    void clearIdentity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void realLouvainClusterBecomesCaseSourceWithinTenantAndEventScope() throws Exception {
        // 1~5. 인증된 반입 요청이 실제 FastAPI와 GDS Louvain 분석을 거쳐 MySQL에 저장됩니다.
        analyze(ingestA, "a".repeat(64));
        analyze(ingestB, "e".repeat(64));
        List<MacroAuditEvidence> storedA = evidenceOf(tenantA);
        List<MacroAuditEvidence> storedB = evidenceOf(tenantB);
        assertThat(storedA).hasSize(2).allSatisfy(row -> {
            assertThat(row.getInternalEventId()).isEqualTo(eventA);
            assertThat(row.getAnalysisRequestId()).matches(UUID_PATTERN)
                    .isEqualTo(storedA.getFirst().getAnalysisRequestId());
            assertThat(row.getClusterId()).isNotBlank().isEqualTo(storedA.getFirst().getClusterId());
            assertThat(row.getAnalysisAlgorithm()).isEqualTo("LOUVAIN");
            assertThat(row.getAnalysisAlgorithmVersion()).contains("gds-2.6.6");
            assertThat(row.getRiskScore()).isBetween(0.0, 1.0);
        });
        assertThat(storedA).extracting(MacroAuditEvidence::getReservationNo)
                .containsExactlyInAnyOrder("res-1-" + run, "res-2-" + run);
        assertThat(storedB).hasSize(2).allSatisfy(row -> assertThat(row.getInternalEventId()).isEqualTo(eventB));
        String requestIdA = storedA.getFirst().getAnalysisRequestId();
        String requestIdB = storedB.getFirst().getAnalysisRequestId();
        assertThat(requestIdB).matches(UUID_PATTERN).isNotEqualTo(requestIdA);

        // 6. 관리자 Track 1 API로 목록과 상세를 조회합니다.
        JsonNode item = read(mvc.perform(get("/api/v1/admin/track1/clusters")
                        .param("eventId", eventA.toString()).header(HttpHeaders.AUTHORIZATION, admin(analystA)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(1))
                .andReturn().getResponse().getContentAsString()).get("items").get(0);
        String readRequestId = item.get("analysisRequestId").asText();
        String readClusterId = item.get("clusterId").asText();
        assertThat(readRequestId).isEqualTo(requestIdA);
        assertThat(readClusterId).isEqualTo(storedA.getFirst().getClusterId());
        mvc.perform(detail(eventA, readRequestId, readClusterId).header(HttpHeaders.AUTHORIZATION, admin(analystA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members.length()").value(2))
                .andExpect(jsonPath("$.cluster.storedMemberCount").value(2));

        // 9. 다른 고객사 공연과 군집은 조회와 Case 연결 모두 404로 숨깁니다.
        mvc.perform(detail(eventB, requestIdB, storedB.getFirst().getClusterId())
                        .header(HttpHeaders.AUTHORIZATION, admin(analystA)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/admin/track1/clusters").param("eventId", eventB.toString())
                        .header(HttpHeaders.AUTHORIZATION, admin(analystB)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].analysisRequestId").value(requestIdB));
        mvc.perform(createGraphCase(analystA, eventA, requestIdB, storedB.getFirst().getClusterId(),
                        UUID.randomUUID().toString()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(createGraphCase(analystA, eventB, readRequestId, readClusterId, UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());

        // 7~8. 조회한 ID 그대로 Case를 만들고 출처가 저장된 분석 증거와 같은지 확인합니다.
        String createKey = UUID.randomUUID().toString();
        JsonNode created = read(mvc.perform(createGraphCase(analystA, eventA, readRequestId, readClusterId, createKey))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.type").value("GRAPH_CLUSTER"))
                .andReturn().getResponse().getContentAsString());
        UUID caseId = UUID.fromString(created.get("caseId").asText());
        mvc.perform(get("/api/v1/cases/" + caseId + "/sources").header(HttpHeaders.AUTHORIZATION, admin(analystA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].analysisRequestId").value(requestIdA))
                .andExpect(jsonPath("$[0].clusterId").value(readClusterId))
                .andExpect(jsonPath("$[0].storedMemberCount").value(2));
        mvc.perform(get("/api/v1/cases/" + caseId).header(HttpHeaders.AUTHORIZATION, admin(analystB)))
                .andExpect(status().isNotFound());
        CaseSourceReference source = sources.findAll().stream()
                .filter(row -> caseId.equals(row.getCaseId())).findFirst().orElseThrow();
        assertThat(source.getTenantId()).isEqualTo(tenantA);
        assertThat(source.getEventId()).isEqualTo(eventA);
        assertThat(source.getSourceType()).isEqualTo(CaseSourceReference.Type.STORED_CLUSTER_EVIDENCE);
        assertThat(source.getAnalysisRequestId()).isEqualTo(requestIdA);
        assertThat(source.getClusterId()).isEqualTo(readClusterId);
        assertThat(source.getAnchorAuditId()).isIn(storedA.stream().map(MacroAuditEvidence::getAuditId).toList());
        assertThat(source.getRiskScore()).isEqualTo(storedA.getFirst().getRiskScore());

        // 같은 멱등성 키 재전송은 같은 Case를 돌려주고, 새 키로 같은 군집을 연결하면 중복 출처로 거부합니다.
        mvc.perform(createGraphCase(analystA, eventA, readRequestId, readClusterId, createKey))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.caseId").value(caseId.toString()));
        mvc.perform(createGraphCase(analystA, eventA, readRequestId, readClusterId, UUID.randomUUID().toString()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SOURCE_ALREADY_LINKED"));

        // 10. 잘못된 입력과 분석 실패는 기존 증거와 Case 상태를 바꾸지 않습니다.
        Case before = cases.findById(caseId).orElseThrow();
        long caseCount = casesOf(tenantA);
        long sourceCount = sources.findAll().stream().filter(row -> tenantA.equals(row.getTenantId())).count();
        long activityCount = activities.findAll().stream().filter(row -> tenantA.equals(row.getTenantId())).count();
        long keyCount = idempotency.findAll().stream().filter(row -> tenantA.equals(row.getTenantId())).count();
        String badKey = UUID.randomUUID().toString();
        mvc.perform(createGraphCase(analystA, eventA, readRequestId.toUpperCase(Locale.ROOT), readClusterId, badKey))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(createGraphCase(analystA, eventA, "run-1", readClusterId, badKey))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(createGraphCase(analystA, eventA, readRequestId, "missing-" + run, badKey))
                .andExpect(status().isNotFound());

        SecurityContextHolder.getContext().setAuthentication(serviceAuthentication(ingestA));
        AgentAnalysisRequest rejectedByFastApi = new AgentAnalysisRequest(List.of(
                new AgentAnalysisRequest.TicketData("uid-1-" + run, "res-1-" + run, externalEvent,
                        "invalid-token", null, null, null),
                new AgentAnalysisRequest.TicketData("uid-9-" + run, "res-9-" + run, externalEvent,
                        "invalid-token", null, null, null)));
        assertThat(analysis.processAgentData(rejectedByFastApi)).isEqualTo(FraudAnalysisStatus.UNAVAILABLE);
        SecurityContextHolder.clearContext();

        assertThat(casesOf(tenantA)).isEqualTo(caseCount);
        assertThat(sources.findAll().stream().filter(row -> tenantA.equals(row.getTenantId())).count())
                .isEqualTo(sourceCount);
        assertThat(activities.findAll().stream().filter(row -> tenantA.equals(row.getTenantId())).count())
                .isEqualTo(activityCount);
        assertThat(idempotency.findAll().stream().filter(row -> tenantA.equals(row.getTenantId())).count())
                .isEqualTo(keyCount);
        assertThat(evidenceOf(tenantA).stream().map(Track1CaseLinkLiveMySqlTest::fingerprint).toList())
                .containsExactlyInAnyOrderElementsOf(storedA.stream()
                        .map(Track1CaseLinkLiveMySqlTest::fingerprint).toList());
        Case after = cases.findById(caseId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(before.getStatus());
        assertThat(after.getCaseVersion()).isEqualTo(before.getCaseVersion());
        assertThat(after.getEvidenceRevision()).isEqualTo(before.getEvidenceRevision());
    }

    private void analyze(String subject, String sharedPaymentHash) throws Exception {
        String body = """
                {"tickets":[
                {"accountId":"uid-1-%1$s","reservationNo":"res-1-%1$s","eventId":"%2$s","paymentHash":"%3$s"},
                {"accountId":"uid-2-%1$s","reservationNo":"res-2-%1$s","eventId":"%2$s","paymentHash":"%3$s"},
                {"accountId":"uid-3-%1$s","reservationNo":"res-3-%1$s","eventId":"%2$s","paymentHash":"%4$s"}]}
                """.formatted(run, externalEvent, sharedPaymentHash, "c".repeat(64));
        mvc.perform(post("/api/v1/audits/analyze").header(HttpHeaders.AUTHORIZATION, service(subject))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private List<MacroAuditEvidence> evidenceOf(UUID tenantId) {
        return evidence.findAll().stream().filter(row -> tenantId.equals(row.getTenantId())).toList();
    }

    private static String fingerprint(MacroAuditEvidence row) {
        return String.join("|", String.valueOf(row.getAuditId()), row.getAnalysisRequestId(), row.getClusterId(),
                String.valueOf(row.getRiskScore()), row.getReservationNo(), String.valueOf(row.getInternalEventId()),
                String.valueOf(row.getAnalysisCompletedAt()));
    }

    private long casesOf(UUID tenantId) {
        return cases.findAll().stream().filter(row -> tenantId.equals(row.getTenantId())).count();
    }

    private void analyst(String subject, UUID tenantId, UUID eventId) {
        UUID membership = UUID.randomUUID();
        memberships.saveAndFlush(new AdminMembershipEntity(membership, tenantId, subject, AccessRecordStatus.ACTIVE));
        roles.saveAndFlush(new MembershipRole(UUID.randomUUID(), membership, AdminRole.ANALYST));
        grants.saveAndFlush(new MembershipEventGrant(UUID.randomUUID(), membership, tenantId, eventId));
    }

    private JsonNode read(String body) {
        return json.readTree(body);
    }

    private static MockHttpServletRequestBuilder detail(UUID eventId, String requestId, String clusterId) {
        return get("/api/v1/admin/track1/clusters/detail").param("eventId", eventId.toString())
                .param("analysisRequestId", requestId).param("clusterId", clusterId);
    }

    private MockHttpServletRequestBuilder createGraphCase(String subject, UUID eventId, String requestId,
            String clusterId, String key) throws Exception {
        CaseDto.Create input = new CaseDto.Create(eventId, Case.Type.GRAPH_CLUSTER,
                List.of(new CaseDto.GraphCluster(requestId, clusterId)), List.of(), List.of(),
                "Investigate stored graph evidence", null, null);
        return post("/api/v1/cases").header(HttpHeaders.AUTHORIZATION, admin(subject))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input));
    }

    private static String admin(String subject) throws Exception {
        return "Bearer " + sign(new JWTClaimsSet.Builder().issuer(ADMIN_ISSUER)
                .audience("ticket-defender-admin").subject(subject));
    }

    private static String service(String subject) throws Exception {
        return "Bearer " + sign(new JWTClaimsSet.Builder().issuer(SERVICE_ISSUER)
                .audience("ticket-defender-analysis").subject(subject).claim("client_id", SERVICE_CLIENT));
    }

    private static JwtAuthenticationToken serviceAuthentication(String subject) {
        JwtAuthenticationToken token = new JwtAuthenticationToken(
                org.springframework.security.oauth2.jwt.Jwt.withTokenValue("service")
                        .header("alg", "RS256").issuer(SERVICE_ISSUER).subject(subject)
                        .claim("client_id", SERVICE_CLIENT)
                        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build());
        token.setAuthenticated(true);
        return token;
    }

    private static String sign(JWTClaimsSet.Builder claims) throws Exception {
        SignedJWT token = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256),
                claims.expirationTime(Date.from(Instant.now().plusSeconds(300))).build());
        token.sign(new RSASSASigner(keyPair.getPrivate()));
        return token.serialize();
    }
}
