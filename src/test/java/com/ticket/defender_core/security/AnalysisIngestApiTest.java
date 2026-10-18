package com.ticket.defender_core.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.ticket.defender_core.adapter.out.api.FastApiAdapter;
import com.ticket.defender_core.adapter.out.api.dto.FastApiClusterResponse;
import com.ticket.defender_core.adapter.out.api.dto.MacroAnalysisRequest;
import com.ticket.defender_core.adapter.out.api.dto.MacroAnalysisResponse;
import com.ticket.defender_core.adapter.out.persistence.MacroAuditEvidenceRepository;
import com.ticket.defender_core.adapter.out.persistence.ServiceTenantBindingRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantEventRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantRepository;
import com.ticket.defender_core.domain.AccessRecordStatus;
import com.ticket.defender_core.domain.ServiceTenantBinding;
import com.ticket.defender_core.domain.Tenant;
import com.ticket.defender_core.domain.TenantEvent;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ingest-api;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"
})
@AutoConfigureMockMvc
@Transactional
class AnalysisIngestApiTest {
    private static final String ISSUER = "https://issuer.example.test/service";
    private static final String AUDIENCE = "ticket-defender-analysis";
    private static final String CLIENT_ID = "trusted-ingest";
    private static final UUID TENANT_A = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID EVENT_A = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID EVENT_B = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID INACTIVE_EVENT = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static KeyPair keyPair;

    @Autowired private MockMvc mvc;
    @Autowired private TenantRepository tenants;
    @Autowired private TenantEventRepository events;
    @Autowired private ServiceTenantBindingRepository bindings;
    @Autowired private MacroAuditEvidenceRepository evidence;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    @MockitoBean(name = "analysisJwtDecoder")
    private JwtDecoder analysisDecoder;

    @MockitoBean
    private FastApiAdapter fastApiAdapter;

    @BeforeAll
    static void createKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
    }

    @BeforeEach
    void seed() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keyPair.getPublic()).build();
        decoder.setJwtValidator(JwtDecoderFactory.validator(ISSUER, AUDIENCE, CLIENT_ID));
        given(analysisDecoder.decode(anyString())).willAnswer(call -> decoder.decode(call.getArgument(0)));

        tenants.save(new Tenant(TENANT_A, "고객사 A", AccessRecordStatus.ACTIVE));
        tenants.save(new Tenant(TENANT_B, "고객사 B", AccessRecordStatus.ACTIVE));
        events.save(new TenantEvent(EVENT_A, TENANT_A, "shared-event", "공연 A", AccessRecordStatus.ACTIVE));
        events.save(new TenantEvent(EVENT_B, TENANT_B, "shared-event", "공연 B", AccessRecordStatus.ACTIVE));
        events.save(new TenantEvent(UUID.randomUUID(), TENANT_B, "tenant-b-only", "공연 B 전용", AccessRecordStatus.ACTIVE));
        events.save(new TenantEvent(INACTIVE_EVENT, TENANT_A, "inactive-event", "비활성 공연", AccessRecordStatus.INACTIVE));
        bindings.save(new ServiceTenantBinding(UUID.randomUUID(), ISSUER, CLIENT_ID,
                "ingest-a", TENANT_A, AccessRecordStatus.ACTIVE));
        bindings.save(new ServiceTenantBinding(UUID.randomUUID(), ISSUER, CLIENT_ID,
                "ingest-b", TENANT_B, AccessRecordStatus.ACTIVE));
    }

    @Test
    void verifiedServiceStoresOnlyItsTenantAndEventScope() throws Exception {
        completedAnalysis();

        mvc.perform(post("/api/v1/audits/analyze")
                        .header(HttpHeaders.AUTHORIZATION, bearer("ingest-a", CLIENT_ID, true))
                        .contentType(MediaType.APPLICATION_JSON).content(body("shared-event")))
                .andExpect(status().isOk());

        assertThat(evidence.findAllByTenantIdAndInternalEventId(TENANT_A, EVENT_A)).hasSize(2);
        assertThat(evidence.findAllByTenantIdAndInternalEventId(TENANT_B, EVENT_B)).isEmpty();
    }

    @Test
    void invalidTokenAndWrongClientAreRejectedBeforeAnalysis() throws Exception {
        mvc.perform(post("/api/v1/audits/analyze").contentType(MediaType.APPLICATION_JSON)
                        .content(body("shared-event")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/audits/analyze")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer broken")
                        .contentType(MediaType.APPLICATION_JSON).content(body("shared-event")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/audits/analyze")
                        .header(HttpHeaders.AUTHORIZATION, bearer("ingest-a", "wrong-client", false))
                        .contentType(MediaType.APPLICATION_JSON).content(body("shared-event")))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(fastApiAdapter);
        assertThat(evidence.count()).isZero();
    }

    @Test
    void unmappedOrInactiveServiceTenantCannotAnalyze() throws Exception {
        mvc.perform(post("/api/v1/audits/analyze")
                        .header(HttpHeaders.AUTHORIZATION, bearer("unknown", CLIENT_ID, false))
                        .contentType(MediaType.APPLICATION_JSON).content(body("shared-event")))
                .andExpect(status().isForbidden());
        jdbc.update("UPDATE tenants SET status='INACTIVE' WHERE id=?", TENANT_A.toString());
        entityManager.clear();
        mvc.perform(post("/api/v1/audits/analyze")
                        .header(HttpHeaders.AUTHORIZATION, bearer("ingest-a", CLIENT_ID, false))
                        .contentType(MediaType.APPLICATION_JSON).content(body("shared-event")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(fastApiAdapter);
        assertThat(evidence.count()).isZero();
    }

    @Test
    void inactiveOrForeignEventRejectsWholeBatchBeforeAnalysis() throws Exception {
        mvc.perform(post("/api/v1/audits/analyze")
                        .header(HttpHeaders.AUTHORIZATION, bearer("ingest-a", CLIENT_ID, false))
                        .contentType(MediaType.APPLICATION_JSON).content(body("inactive-event")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/audits/analyze")
                        .header(HttpHeaders.AUTHORIZATION, bearer("ingest-a", CLIENT_ID, false))
                        .contentType(MediaType.APPLICATION_JSON).content(body("tenant-b-only")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/audits/analyze")
                        .header(HttpHeaders.AUTHORIZATION, bearer("ingest-a", CLIENT_ID, false))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("shared-event").replaceFirst("\"eventId\":\"shared-event\"",
                                "\"eventId\":\"tenant-b-only\"")))
                .andExpect(status().isNotFound());
        verifyNoInteractions(fastApiAdapter);
        assertThat(evidence.count()).isZero();
    }

    @Test
    void inactiveServiceBindingCannotAnalyze() throws Exception {
        jdbc.update("UPDATE service_tenant_bindings SET status='INACTIVE' WHERE subject='ingest-a'");
        entityManager.clear();
        mvc.perform(post("/api/v1/audits/analyze")
                        .header(HttpHeaders.AUTHORIZATION, bearer("ingest-a", CLIENT_ID, false))
                        .contentType(MediaType.APPLICATION_JSON).content(body("shared-event")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(fastApiAdapter);
    }

    @Test
    void unavailableAnalysisDoesNotPersistEvidence() throws Exception {
        given(fastApiAdapter.requestMacroAnalysis(any(MacroAnalysisRequest.class)))
                .willAnswer(call -> MacroAnalysisResponse.unavailable(
                        ((MacroAnalysisRequest) call.getArgument(0)).requestId()));
        mvc.perform(post("/api/v1/audits/analyze")
                        .header(HttpHeaders.AUTHORIZATION, bearer("ingest-a", CLIENT_ID, false))
                        .contentType(MediaType.APPLICATION_JSON).content(body("shared-event")))
                .andExpect(status().isServiceUnavailable());
        assertThat(evidence.count()).isZero();
    }

    @Test
    void sameExternalIdentifiersCanBeStoredIndependentlyByVerifiedSubjects() throws Exception {
        completedAnalysis();
        mvc.perform(post("/api/v1/audits/analyze")
                        .header(HttpHeaders.AUTHORIZATION, bearer("ingest-a", CLIENT_ID, false))
                        .contentType(MediaType.APPLICATION_JSON).content(body("shared-event")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/audits/analyze")
                        .header(HttpHeaders.AUTHORIZATION, bearer("ingest-b", CLIENT_ID, false))
                        .contentType(MediaType.APPLICATION_JSON).content(body("shared-event")))
                .andExpect(status().isOk());

        assertThat(evidence.findAllByTenantIdAndInternalEventId(TENANT_A, EVENT_A)).hasSize(2);
        assertThat(evidence.findAllByTenantIdAndInternalEventId(TENANT_B, EVENT_B)).hasSize(2);
        assertThat(evidence.count()).isEqualTo(4);
    }

    @Test
    void sameTenantDuplicateRequestRemainsIdempotent() throws Exception {
        completedAnalysis();
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/api/v1/audits/analyze")
                            .header(HttpHeaders.AUTHORIZATION, bearer("ingest-a", CLIENT_ID, false))
                            .contentType(MediaType.APPLICATION_JSON).content(body("shared-event")))
                    .andExpect(status().isOk());
        }
        assertThat(evidence.count()).isEqualTo(2);
    }

    private void completedAnalysis() {
        given(fastApiAdapter.requestMacroAnalysis(any(MacroAnalysisRequest.class))).willAnswer(call -> {
            MacroAnalysisRequest request = call.getArgument(0);
            FastApiClusterResponse cluster = new FastApiClusterResponse("cluster-1", List.of(
                    new FastApiClusterResponse.Member("account-1", "reservation-1", "shared-event"),
                    new FastApiClusterResponse.Member("account-2", "reservation-2", "shared-event")),
                    List.of("a".repeat(64)), List.of(), List.of(), List.of(), 0.81);
            return new MacroAnalysisResponse(request.requestId(), MacroAnalysisResponse.Status.COMPLETED,
                    "LOUVAIN", "1.0.0", OffsetDateTime.parse("2026-10-08T10:15:30Z"), List.of(cluster));
        });
    }

    private static String body(String eventId) {
        return """
                {"tickets":[
                {"accountId":"account-1","reservationNo":"reservation-1","eventId":"%s","paymentHash":"%s"},
                {"accountId":"account-2","reservationNo":"reservation-2","eventId":"%s","paymentHash":"%s"}]}
                """.formatted(eventId, "a".repeat(64), eventId, "a".repeat(64));
    }

    private static String bearer(String subject, String clientId, boolean forgedTenantClaim) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER).audience(AUDIENCE).subject(subject)
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("client_id", clientId);
        if (forgedTenantClaim) {
            claims.claim("tenantId", TENANT_B.toString());
            claims.claim("roles", List.of("TENANT_ADMIN"));
        }
        SignedJWT token = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
        token.sign(new RSASSASigner(keyPair.getPrivate()));
        return "Bearer " + token.serialize();
    }
}
