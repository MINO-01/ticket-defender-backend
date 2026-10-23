package com.ticket.defender_core.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.ticket.defender_core.adapter.out.persistence.AdminMembershipRepository;
import com.ticket.defender_core.adapter.out.persistence.MembershipEventGrantRepository;
import com.ticket.defender_core.adapter.out.persistence.MembershipRoleRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantEventRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantRepository;
import com.ticket.defender_core.adapter.out.persistence.TicketAuditRepository;
import com.ticket.defender_core.domain.AccessRecordStatus;
import com.ticket.defender_core.domain.AdminMembershipEntity;
import com.ticket.defender_core.domain.MacroAnalysisEvidence;
import com.ticket.defender_core.domain.MacroEvidenceKey;
import com.ticket.defender_core.domain.MembershipEventGrant;
import com.ticket.defender_core.domain.MembershipRole;
import com.ticket.defender_core.domain.Tenant;
import com.ticket.defender_core.domain.TenantEvent;
import com.ticket.defender_core.domain.TicketAudit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:admin-track1-api;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"
})
@AutoConfigureMockMvc
@Transactional
class AdminTrack1ClusterApiTest {
    private static final String ADMIN_ISSUER = "https://issuer.example.test/admin";
    private static final String ADMIN_AUDIENCE = "ticket-defender-admin";
    private static final UUID TENANT_A = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID EVENT_A = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID EVENT_B = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID EVENT_A_OTHER = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID MEMBERSHIP_A = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static KeyPair keyPair;

    @Autowired private MockMvc mvc;
    @Autowired private TenantRepository tenants;
    @Autowired private TenantEventRepository events;
    @Autowired private AdminMembershipRepository memberships;
    @Autowired private MembershipRoleRepository roles;
    @Autowired private MembershipEventGrantRepository eventGrants;
    @Autowired private TicketAuditRepository audits;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean(name = "adminJwtDecoder")
    private JwtDecoder adminDecoder;

    @BeforeAll
    static void createKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
    }

    @BeforeEach
    void seed() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keyPair.getPublic()).build();
        decoder.setJwtValidator(JwtDecoderFactory.validator(ADMIN_ISSUER, ADMIN_AUDIENCE, null));
        given(adminDecoder.decode(anyString())).willAnswer(call -> decoder.decode(call.getArgument(0)));

        tenants.save(new Tenant(TENANT_A, "고객사 A", AccessRecordStatus.ACTIVE));
        tenants.save(new Tenant(TENANT_B, "고객사 B", AccessRecordStatus.ACTIVE));
        events.save(new TenantEvent(EVENT_A, TENANT_A, "shared-event", "공연 A", AccessRecordStatus.ACTIVE));
        events.save(new TenantEvent(EVENT_B, TENANT_B, "shared-event", "공연 B", AccessRecordStatus.ACTIVE));
        events.save(new TenantEvent(EVENT_A_OTHER, TENANT_A, "other-event", "다른 공연", AccessRecordStatus.ACTIVE));
        memberships.save(new AdminMembershipEntity(MEMBERSHIP_A, TENANT_A, "admin-a", AccessRecordStatus.ACTIVE));
        roles.save(new MembershipRole(UUID.randomUUID(), MEMBERSHIP_A, AdminRole.ANALYST));
        eventGrants.save(new MembershipEventGrant(UUID.randomUUID(), MEMBERSHIP_A, TENANT_A, EVENT_A));

        UUID membershipB = UUID.randomUUID();
        memberships.save(new AdminMembershipEntity(membershipB, TENANT_B, "admin-b", AccessRecordStatus.ACTIVE));
        roles.save(new MembershipRole(UUID.randomUUID(), membershipB, AdminRole.REVIEWER));
        eventGrants.save(new MembershipEventGrant(UUID.randomUUID(), membershipB, TENANT_B, EVENT_B));

        UUID noRole = UUID.randomUUID();
        memberships.save(new AdminMembershipEntity(noRole, TENANT_A, "no-role", AccessRecordStatus.ACTIVE));
        eventGrants.save(new MembershipEventGrant(UUID.randomUUID(), noRole, TENANT_A, EVENT_A));
    }

    @Test
    void authorizedAdminReadsOnlyStoredMembersAndMetadata() throws Exception {
        saveEvidence(TENANT_A, EVENT_A, "account-1", "reservation-1", "run-1", "cluster-1", 0.82, "2026-10-08T10:15:30");
        saveEvidence(TENANT_A, EVENT_A, "account-2", "reservation-2", "run-1", "cluster-1", 0.82, "2026-10-08T10:15:30");

        mvc.perform(list(EVENT_A).header(HttpHeaders.AUTHORIZATION, bearer("admin-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.event.id").value(EVENT_A.toString()))
                .andExpect(jsonPath("$.event.externalEventId").value("shared-event"))
                .andExpect(jsonPath("$.event.name").value("공연 A"))
                .andExpect(jsonPath("$.coverage").value("STORED_EVIDENCE_ONLY"))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].analysisRequestId").value("run-1"))
                .andExpect(jsonPath("$.items[0].riskScore").value(0.82))
                .andExpect(jsonPath("$.items[0].analysisAlgorithm").value("LOUVAIN"))
                .andExpect(jsonPath("$.items[0].analysisAlgorithmVersion").value("1.0.0"))
                .andExpect(jsonPath("$.items[0].storedMemberCount").value(2));
        mvc.perform(detail(EVENT_A, "run-1", "cluster-1").header(HttpHeaders.AUTHORIZATION, bearer("admin-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cluster.analyzedAt").value("2026-10-08T10:15:30Z"))
                .andExpect(jsonPath("$.members.length()").value(2))
                .andExpect(jsonPath("$.members[0].accountId").value("account-1"))
                .andExpect(jsonPath("$.members[0].reservationNo").value("reservation-1"))
                .andExpect(jsonPath("$.members[0].auditId").isNumber())
                .andExpect(jsonPath("$.members[0].paymentHash").doesNotExist())
                .andExpect(jsonPath("$.members[0].seat").doesNotExist())
                .andExpect(jsonPath("$.members[0].signals").doesNotExist())
                .andExpect(content().string(not(containsString("FRAUD_DETECTED"))))
                .andExpect(content().string(not(containsString("a".repeat(64)))));
    }

    @Test
    void authorizedEventWithoutStoredEvidenceReturnsAnEmptyPage() throws Exception {
        mvc.perform(list(EVENT_A).header(HttpHeaders.AUTHORIZATION, bearer("admin-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totalItems").value(0))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    void missingInvalidAndServiceAudienceTokensAreRejected() throws Exception {
        mvc.perform(list(EVENT_A)).andExpect(status().isUnauthorized());
        mvc.perform(list(EVENT_A).header(HttpHeaders.AUTHORIZATION, "Bearer broken"))
                .andExpect(status().isUnauthorized());
        mvc.perform(list(EVENT_A).header(HttpHeaders.AUTHORIZATION,
                        token("admin-a", "https://issuer.example.test/service", "ticket-defender-analysis")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void roleAndEventScopeAreCheckedBeforeEvidenceQuery() throws Exception {
        mvc.perform(list(EVENT_A).header(HttpHeaders.AUTHORIZATION, bearer("no-role")))
                .andExpect(status().isForbidden());
        mvc.perform(list(EVENT_B).header(HttpHeaders.AUTHORIZATION, bearer("admin-a")))
                .andExpect(status().isNotFound());
        mvc.perform(list(EVENT_A_OTHER).header(HttpHeaders.AUTHORIZATION, bearer("admin-a")))
                .andExpect(status().isNotFound());
        mvc.perform(detail(EVENT_B, "run-1", "cluster-1")
                        .header(HttpHeaders.AUTHORIZATION, bearer("admin-a")))
                .andExpect(status().isNotFound());
    }

    @Test
    void sameRunAndClusterStaySeparateAcrossEventsInOneTenant() throws Exception {
        eventGrants.save(new MembershipEventGrant(UUID.randomUUID(), MEMBERSHIP_A, TENANT_A, EVENT_A_OTHER));
        saveEvidence(TENANT_A, EVENT_A, "account-a", "reservation-a", "same-run", "same-cluster", 0.7,
                "2026-10-08T10:15:30");
        saveEvidence(TENANT_A, EVENT_A_OTHER, "account-other", "reservation-other", "same-run", "same-cluster",
                0.7, "2026-10-08T10:15:30");

        mvc.perform(detail(EVENT_A, "same-run", "same-cluster")
                        .header(HttpHeaders.AUTHORIZATION, bearer("admin-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members.length()").value(1))
                .andExpect(jsonPath("$.members[0].accountId").value("account-a"));
        mvc.perform(detail(EVENT_A_OTHER, "same-run", "same-cluster")
                        .header(HttpHeaders.AUTHORIZATION, bearer("admin-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members.length()").value(1))
                .andExpect(jsonPath("$.members[0].accountId").value("account-other"));
    }

    @Test
    void legacyEvidenceAndSameExternalEventInOtherTenantStayHidden() throws Exception {
        saveEvidence(TENANT_A, EVENT_A, "same-account", "same-reservation", "shared-run", "shared-cluster", 0.8,
                "2026-10-08T10:15:30");
        saveEvidence(TENANT_B, EVENT_B, "same-account", "same-reservation", "shared-run", "shared-cluster", 0.8,
                "2026-10-08T10:15:30");
        insertLegacyEvidence();

        mvc.perform(list(EVENT_A).header(HttpHeaders.AUTHORIZATION, bearer("admin-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].storedMemberCount").value(1));
        mvc.perform(list(EVENT_B).header(HttpHeaders.AUTHORIZATION, bearer("admin-b")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.event.name").value("공연 B"))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].storedMemberCount").value(1));
        mvc.perform(detail(EVENT_A, "legacy-run", "legacy-cluster")
                        .header(HttpHeaders.AUTHORIZATION, bearer("admin-a")))
                .andExpect(status().isNotFound());
    }

    @Test
    void analysisRequestIdSeparatesRepeatedClusterIds() throws Exception {
        saveEvidence(TENANT_A, EVENT_A, "account-1", "reservation-1", "run/1", "cluster/x", 0.7,
                "2026-10-08T10:15:30");
        saveEvidence(TENANT_A, EVENT_A, "account-2", "reservation-2", "run/2", "cluster/x", 0.9,
                "2026-10-09T10:15:30");

        mvc.perform(list(EVENT_A).header(HttpHeaders.AUTHORIZATION, bearer("admin-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items[0].analysisRequestId").value("run/2"));
        mvc.perform(detail(EVENT_A, "run/1", "cluster/x").header(HttpHeaders.AUTHORIZATION, bearer("admin-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members[0].accountId").value("account-1"))
                .andExpect(jsonPath("$.members.length()").value(1));
        mvc.perform(detail(EVENT_A, "run/2", "cluster/x").header(HttpHeaders.AUTHORIZATION, bearer("admin-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members[0].accountId").value("account-2"));
        mvc.perform(detail(EVENT_A, "missing", "cluster/x").header(HttpHeaders.AUTHORIZATION, bearer("admin-a")))
                .andExpect(status().isNotFound());
    }

    @Test
    void sortingAndPaginationUseStableClusterKeys() throws Exception {
        saveEvidence(TENANT_A, EVENT_A, "account-1", "reservation-1", "run-1", "cluster-a", 0.9,
                "2026-10-08T10:15:30");
        saveEvidence(TENANT_A, EVENT_A, "account-2", "reservation-2", "run-2", "cluster-b", 0.7,
                "2026-10-10T10:15:30");
        saveEvidence(TENANT_A, EVENT_A, "account-3", "reservation-3", "run-3", "cluster-c", 0.5,
                "2026-10-09T10:15:30");

        mvc.perform(list(EVENT_A).param("page", "1").param("pageSize", "1")
                        .header(HttpHeaders.AUTHORIZATION, bearer("admin-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(1))
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.items[0].clusterId").value("cluster-b"));
        mvc.perform(list(EVENT_A).param("sort", "priority-asc")
                        .header(HttpHeaders.AUTHORIZATION, bearer("admin-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].clusterId").value("cluster-c"));
        mvc.perform(list(EVENT_A).param("sort", "recent")
                        .header(HttpHeaders.AUTHORIZATION, bearer("admin-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].clusterId").value("cluster-b"));
        mvc.perform(list(EVENT_A).param("pageSize", "101")
                        .header(HttpHeaders.AUTHORIZATION, bearer("admin-a")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/track1/clusters").param("eventId", "shared-event")
                        .header(HttpHeaders.AUTHORIZATION, bearer("admin-a")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void otherRoutesStillUseDefaultDenyAndOfficialPdfIsBlocked() throws Exception {
        mvc.perform(get("/api/v1/cases/unimplemented/route")
                        .header(HttpHeaders.AUTHORIZATION, bearer("admin-a")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/track1/clusters")
                        .header(HttpHeaders.AUTHORIZATION, bearer("admin-a")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/audits/123/report")
                        .header(HttpHeaders.AUTHORIZATION, bearer("admin-a")))
                .andExpect(status().isConflict());
    }

    private void saveEvidence(UUID tenantId, UUID eventId, String accountId, String reservationNo,
            String analysisRequestId, String clusterId, double riskScore, String analyzedAt) {
        audits.saveAndFlush(TicketAudit.createMacroAudit(new MacroAnalysisEvidence(
                accountId, reservationNo, eventId.equals(EVENT_A_OTHER) ? "other-event" : "shared-event",
                "a".repeat(64), null, "b".repeat(64), null,
                clusterId, riskScore, "LOUVAIN", "1.0.0", analysisRequestId,
                LocalDateTime.parse(analyzedAt), tenantId, eventId)));
    }

    private void insertLegacyEvidence() {
        jdbc.update("INSERT INTO ticket_audit (account_id,reservation_no,evidence_type,status) VALUES (?,?,?,?)",
                "legacy-account", "legacy-reservation", "MACRO_GRAPH", "FRAUD_DETECTED");
        Long auditId = jdbc.queryForObject("SELECT MAX(id) FROM ticket_audit", Long.class);
        jdbc.update("""
                INSERT INTO macro_audit_evidence (audit_id,evidence_key,event_id,reservation_no,account_id,
                cluster_id,risk_score,analysis_algorithm,analysis_algorithm_version,analysis_request_id,
                analysis_completed_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, auditId, MacroEvidenceKey.create("shared-event", "legacy-reservation", "legacy-account"),
                "shared-event", "legacy-reservation", "legacy-account", "legacy-cluster", 0.9,
                "LOUVAIN", "1.0.0", "legacy-run", LocalDateTime.parse("2026-10-08T10:15:30"));
    }

    private static MockHttpServletRequestBuilder list(UUID eventId) {
        return get("/api/v1/admin/track1/clusters").param("eventId", eventId.toString());
    }

    private static MockHttpServletRequestBuilder detail(UUID eventId, String runId, String clusterId) {
        return get("/api/v1/admin/track1/clusters/detail")
                .param("eventId", eventId.toString())
                .param("analysisRequestId", runId)
                .param("clusterId", clusterId);
    }

    private static String bearer(String subject) throws Exception {
        return token(subject, ADMIN_ISSUER, ADMIN_AUDIENCE);
    }

    private static String token(String subject, String issuer, String audience) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), new JWTClaimsSet.Builder()
                .issuer(issuer).audience(audience).subject(subject)
                .claim("roles", List.of("TENANT_ADMIN"))
                .claim("tenantId", TENANT_B.toString())
                .claim("eventIds", List.of(EVENT_B.toString()))
                .expirationTime(Date.from(Instant.now().plusSeconds(300))).build());
        jwt.sign(new RSASSASigner(keyPair.getPrivate()));
        return "Bearer " + jwt.serialize();
    }
}
