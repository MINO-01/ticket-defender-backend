package com.ticket.defender_core.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.ticket.defender_core.adapter.in.web.dto.AgentAnalysisRequest;
import com.ticket.defender_core.application.service.FraudAnalysisService;
import com.ticket.defender_core.application.service.FraudAnalysisStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:security-api;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"
})
@AutoConfigureMockMvc
@Import(SecurityApiTest.ProbeConfiguration.class)
class SecurityApiTest {
    private static final String ADMIN_ISSUER = "https://issuer.example.test/admin";
    private static final String ADMIN_AUDIENCE = "ticket-defender-admin";
    private static final String ANALYSIS_ISSUER = "https://issuer.example.test/service";
    private static final String ANALYSIS_AUDIENCE = "ticket-defender-analysis";
    private static final String SERVICE_CLIENT = "trusted-ingest";
    private static final UUID TENANT_A = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID EVENT_A = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID EVENT_B = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID EVENT_C = UUID.fromString("55555555-5555-4555-8555-555555555555");

    private static KeyPair adminKey;
    private static KeyPair serviceKey;

    @Autowired
    private MockMvc mvc;

    @Value("${local.server.port}")
    private int port;

    @MockitoBean(name = "adminJwtDecoder")
    private JwtDecoder adminDecoder;

    @MockitoBean(name = "analysisJwtDecoder")
    private JwtDecoder analysisDecoder;

    @MockitoBean
    private AdminMembershipLookup memberships;

    @MockitoBean
    private EventScopeLookup events;

    @MockitoBean
    private FraudAnalysisService fraudAnalysisService;

    @BeforeAll
    static void createKeys() throws Exception {
        adminKey = newRsaKey();
        serviceKey = newRsaKey();
    }

    @BeforeEach
    void configureVerifiedDecodersAndScopes() {
        NimbusJwtDecoder realAdmin = NimbusJwtDecoder.withPublicKey((RSAPublicKey) adminKey.getPublic()).build();
        realAdmin.setJwtValidator(JwtDecoderFactory.validator(ADMIN_ISSUER, ADMIN_AUDIENCE, null));
        given(adminDecoder.decode(anyString())).willAnswer(call -> realAdmin.decode(call.getArgument(0)));

        NimbusJwtDecoder realAnalysis = NimbusJwtDecoder.withPublicKey((RSAPublicKey) serviceKey.getPublic()).build();
        realAnalysis.setJwtValidator(JwtDecoderFactory.validator(ANALYSIS_ISSUER, ANALYSIS_AUDIENCE, SERVICE_CLIENT));
        given(analysisDecoder.decode(anyString())).willAnswer(call -> realAnalysis.decode(call.getArgument(0)));

        given(events.findTenantId(EVENT_A)).willReturn(Optional.of(TENANT_A));
        given(events.findTenantId(EVENT_B)).willReturn(Optional.of(TENANT_B));
        given(events.findTenantId(EVENT_C)).willReturn(Optional.of(TENANT_A));
    }

    @Test
    void apiReturns401WithoutOrWithInvalidAdminAuthentication() throws Exception {
        mvc.perform(get("/__test__/resources/own"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/__test__/resources/own").header(HttpHeaders.AUTHORIZATION, "Bearer broken"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/__test__/resources/own").header(HttpHeaders.AUTHORIZATION, bearer(serviceToken())))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(memberships);
    }

    @Test
    void serverMembershipAndStoredResourceScopeControlAccess() throws Exception {
        given(memberships.findActiveMembership("admin-1", TENANT_A))
                .willReturn(Optional.of(membership(Set.of(AdminRole.ANALYST), Set.of(EVENT_A))));

        mvc.perform(get("/__test__/resources/own").header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(content().string("admin-1"));
        mvc.perform(get("/__test__/resources/other-tenant")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/__test__/resources/other-event")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/__test__/resources/own")
                        .queryParam("tenantId", TENANT_B.toString())
                        .queryParam("eventId", EVENT_A.toString())
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/__test__/resources/own")
                        .queryParam("tenantId", TENANT_A.toString())
                        .queryParam("eventId", EVENT_C.toString())
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
                .andExpect(status().isNotFound());
    }

    @Test
    void wrongRoleOrForgedTokenClaimsCannotGrantAccess() throws Exception {
        given(memberships.findActiveMembership("admin-1", TENANT_A))
                .willReturn(Optional.of(membership(Set.of(AdminRole.REVIEWER), Set.of(EVENT_A))));
        mvc.perform(get("/__test__/resources/own")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminTokenWithForgedClaims())))
                .andExpect(status().isForbidden());
    }

    @Test
    void mismatchedOrMissingStoredMembershipIsHidden() throws Exception {
        given(memberships.findActiveMembership("admin-1", TENANT_A))
                .willReturn(
                        Optional.of(new AdminMembership("another-user", TENANT_A,
                                Set.of(AdminRole.ANALYST), Set.of(EVENT_A))),
                        Optional.of(new AdminMembership("admin-1", TENANT_B,
                                Set.of(AdminRole.ANALYST), Set.of(EVENT_A))));
        mvc.perform(get("/__test__/resources/own").header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/__test__/resources/own").header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
                .andExpect(status().isNotFound());
    }

    @Test
    void analysisApiAcceptsOnlyServiceTokenAndPreservesTrack1Statuses() throws Exception {
        mvc.perform(post("/api/v1/audits/analyze").contentType(MediaType.APPLICATION_JSON)
                        .content(analysisBody()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/audits/analyze").contentType(MediaType.APPLICATION_JSON)
                        .content(analysisBody()).header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/audits/analyze").contentType(MediaType.APPLICATION_JSON)
                        .content(analysisBody()).header(HttpHeaders.AUTHORIZATION, bearer(serviceToken("untrusted-ingest"))))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(fraudAnalysisService);

        given(fraudAnalysisService.processAgentData(any(AgentAnalysisRequest.class)))
                .willReturn(FraudAnalysisStatus.COMPLETED, FraudAnalysisStatus.UNAVAILABLE);
        mvc.perform(post("/api/v1/audits/analyze").contentType(MediaType.APPLICATION_JSON)
                        .content(analysisBody()).header(HttpHeaders.AUTHORIZATION, bearer(serviceToken())))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/audits/analyze").contentType(MediaType.APPLICATION_JSON)
                        .content(analysisBody()).header(HttpHeaders.AUTHORIZATION, bearer(serviceToken())))
                .andExpect(status().isServiceUnavailable());
        verify(fraudAnalysisService, org.mockito.Mockito.times(2))
                .processAgentData(any(AgentAnalysisRequest.class));
    }

    @Test
    void invalidAnalysisInputWithServiceTokenKeepsBadRequest() throws Exception {
        mvc.perform(post("/api/v1/audits/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header(HttpHeaders.AUTHORIZATION, bearer(serviceToken())))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(fraudAnalysisService);
    }

    @Test
    void embeddedServerKeepsInvalidAnalysisInputStatus() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/audits/analyze"))
                .header(HttpHeaders.AUTHORIZATION, bearer(serviceToken()))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
        verifyNoInteractions(fraudAnalysisService);
    }

    @Test
    void officialPdfIsStillBlockedAndOtherRoutesDefaultToDeny() throws Exception {
        mvc.perform(post("/api/v1/audits/123/report"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/audits/123/report")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
                .andExpect(status().isConflict())
                .andExpect(content().string(not(containsString("%PDF-"))));
        mvc.perform(get("/api/v1/cases").header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/error").header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
                .andExpect(status().isForbidden());
    }

    private static AdminMembership membership(Set<AdminRole> roles, Set<UUID> eventIds) {
        return new AdminMembership("admin-1", TENANT_A, roles, eventIds);
    }

    private static String adminToken() throws Exception {
        return token(adminKey, ADMIN_ISSUER, ADMIN_AUDIENCE, "admin-1", null, false);
    }

    private static String adminTokenWithForgedClaims() throws Exception {
        return token(adminKey, ADMIN_ISSUER, ADMIN_AUDIENCE, "admin-1", null, true);
    }

    private static String serviceToken() throws Exception {
        return serviceToken(SERVICE_CLIENT);
    }

    private static String serviceToken(String clientId) throws Exception {
        return token(serviceKey, ANALYSIS_ISSUER, ANALYSIS_AUDIENCE, "ingest-1", clientId, false);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static String token(KeyPair key, String issuer, String audience, String subject,
            String clientId, boolean forgedClaims) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .subject(subject)
                .expirationTime(Date.from(Instant.now().plusSeconds(300)));
        if (clientId != null) {
            claims.claim("client_id", clientId);
        }
        if (forgedClaims) {
            claims.claim("roles", Set.of("ANALYST", "TENANT_ADMIN"));
            claims.claim("tenantId", TENANT_A.toString());
            claims.claim("eventIds", Set.of(EVENT_A.toString()));
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
        jwt.sign(new RSASSASigner(key.getPrivate()));
        return jwt.serialize();
    }

    private static KeyPair newRsaKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String analysisBody() {
        return """
                {"tickets":[{"accountId":"uid-1","reservationNo":"res-1","eventId":"event-1",
                "paymentHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]}
                """;
    }

    @TestConfiguration
    static class ProbeConfiguration {
        @Bean
        ProbeController probeController(AdminAccessService access) {
            return new ProbeController(access);
        }

        @Bean
        @Order(2)
        SecurityFilterChain probeSecurity(HttpSecurity http,
                @Qualifier("adminJwtDecoder") JwtDecoder decoder) throws Exception {
            return http.securityMatcher("/__test__/resources/**")
                    .csrf(AbstractHttpConfigurer::disable)
                    .exceptionHandling(errors -> errors.authenticationEntryPoint(new BearerTokenAuthenticationEntryPoint())
                            .accessDeniedHandler((request, response, exception) -> response.sendError(403)))
                    .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                    .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.decoder(decoder)))
                    .build();
        }
    }

    @RestController
    static class ProbeController {
        private final AdminAccessService access;
        private final Map<String, AdminResourceScope> resources = Map.of(
                "own", new AdminResourceScope(TENANT_A, EVENT_A),
                "other-tenant", new AdminResourceScope(TENANT_B, EVENT_B),
                "other-event", new AdminResourceScope(TENANT_A, EVENT_C));

        ProbeController(AdminAccessService access) {
            this.access = access;
        }

        @GetMapping("/__test__/resources/{id}")
        String getResource(@PathVariable String id,
                @RequestParam(required = false) UUID tenantId,
                @RequestParam(required = false) UUID eventId) {
            AdminResourceScope stored = resources.get(id);
            if (stored == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            AdminResourceScope requested = tenantId == null && eventId == null
                    ? null : new AdminResourceScope(tenantId, eventId);
            return access.requireResourceAccess(stored, requested, Set.of(AdminRole.ANALYST)).subject();
        }
    }
}
