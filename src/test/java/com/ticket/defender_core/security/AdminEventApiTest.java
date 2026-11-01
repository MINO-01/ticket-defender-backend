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
import com.ticket.defender_core.domain.AccessRecordStatus;
import com.ticket.defender_core.domain.AdminMembershipEntity;
import com.ticket.defender_core.domain.MembershipEventGrant;
import com.ticket.defender_core.domain.MembershipRole;
import com.ticket.defender_core.domain.Tenant;
import com.ticket.defender_core.domain.TenantEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 관리자 접근 가능 공연 목록 API의 JWT 인증, 활성 멤버십, 공연 권한, 다중 tenant 차단을 합성 데이터로 검증합니다. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:admin-event-api;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"
})
@AutoConfigureMockMvc
@Transactional
class AdminEventApiTest {
    private static final String ADMIN_ISSUER = "https://issuer.example.test/admin";
    private static final String ADMIN_AUDIENCE = "ticket-defender-admin";
    private static final UUID TENANT_A = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID TENANT_OFF = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID EVENT_A1 = UUID.fromString("a1000000-0000-4000-8000-000000000001");
    private static final UUID EVENT_A2 = UUID.fromString("a2000000-0000-4000-8000-000000000002");
    private static final UUID EVENT_A3_INACTIVE = UUID.fromString("a3000000-0000-4000-8000-000000000003");
    private static final UUID EVENT_A4_UNGRANTED = UUID.fromString("a4000000-0000-4000-8000-000000000004");
    private static final UUID EVENT_A5_SAME_NAME = UUID.fromString("a0500000-0000-4000-8000-000000000005");
    private static final UUID EVENT_B1 = UUID.fromString("b1000000-0000-4000-8000-000000000001");
    private static final UUID EVENT_OFF1 = UUID.fromString("c1000000-0000-4000-8000-000000000001");
    private static KeyPair keyPair;
    private static KeyPair otherKeyPair;

    @Autowired private MockMvc mvc;
    @Autowired private TenantRepository tenants;
    @Autowired private TenantEventRepository events;
    @Autowired private AdminMembershipRepository memberships;
    @Autowired private MembershipRoleRepository roles;
    @Autowired private MembershipEventGrantRepository grants;

    @MockitoBean(name = "adminJwtDecoder")
    private JwtDecoder adminDecoder;

    @BeforeAll
    static void createKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        otherKeyPair = generator.generateKeyPair();
    }

    @BeforeEach
    void seed() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keyPair.getPublic()).build();
        decoder.setJwtValidator(JwtDecoderFactory.validator(ADMIN_ISSUER, ADMIN_AUDIENCE, null));
        given(adminDecoder.decode(anyString())).willAnswer(call -> decoder.decode(call.getArgument(0)));

        tenants.save(new Tenant(TENANT_A, "고객사 A", AccessRecordStatus.ACTIVE));
        tenants.save(new Tenant(TENANT_B, "고객사 B", AccessRecordStatus.ACTIVE));
        tenants.save(new Tenant(TENANT_OFF, "비활성 고객사", AccessRecordStatus.INACTIVE));
        event(EVENT_A1, TENANT_A, "ext-a1", "나 공연", AccessRecordStatus.ACTIVE);
        event(EVENT_A2, TENANT_A, "ext-a2", "가 공연", AccessRecordStatus.ACTIVE);
        event(EVENT_A3_INACTIVE, TENANT_A, "ext-a3", "다 비활성 공연", AccessRecordStatus.INACTIVE);
        event(EVENT_A4_UNGRANTED, TENANT_A, "ext-a4", "라 권한 없는 공연", AccessRecordStatus.ACTIVE);
        event(EVENT_A5_SAME_NAME, TENANT_A, "ext-a5", "가 공연", AccessRecordStatus.ACTIVE);
        event(EVENT_B1, TENANT_B, "ext-a1", "고객사 B 공연", AccessRecordStatus.ACTIVE);
        event(EVENT_OFF1, TENANT_OFF, "ext-off", "비활성 고객사 공연", AccessRecordStatus.ACTIVE);

        member("analyst-a", TENANT_A, AccessRecordStatus.ACTIVE, List.of(AdminRole.ANALYST),
                List.of(EVENT_A1, EVENT_A2, EVENT_A3_INACTIVE, EVENT_A5_SAME_NAME));
        member("reviewer-a1", TENANT_A, AccessRecordStatus.ACTIVE, List.of(AdminRole.REVIEWER), List.of(EVENT_A1));
        member("approver-a", TENANT_A, AccessRecordStatus.ACTIVE, List.of(AdminRole.APPROVER), List.of(EVENT_A2));
        member("tenant-admin-a", TENANT_A, AccessRecordStatus.ACTIVE, List.of(AdminRole.TENANT_ADMIN),
                List.of(EVENT_A2));
        member("no-role-a", TENANT_A, AccessRecordStatus.ACTIVE, List.of(), List.of(EVENT_A2));
        member("no-grant-a", TENANT_A, AccessRecordStatus.ACTIVE, List.of(AdminRole.ANALYST), List.of());
        member("inactive-member-a", TENANT_A, AccessRecordStatus.INACTIVE, List.of(AdminRole.ANALYST),
                List.of(EVENT_A1));
        member("off-tenant-only", TENANT_OFF, AccessRecordStatus.ACTIVE, List.of(AdminRole.ANALYST),
                List.of(EVENT_OFF1));
        member("multi", TENANT_A, AccessRecordStatus.ACTIVE, List.of(AdminRole.ANALYST), List.of(EVENT_A1));
        member("multi", TENANT_B, AccessRecordStatus.ACTIVE, List.of(AdminRole.REVIEWER), List.of(EVENT_B1));
        member("multi-off-tenant", TENANT_A, AccessRecordStatus.ACTIVE, List.of(AdminRole.ANALYST),
                List.of(EVENT_A2));
        member("multi-off-tenant", TENANT_OFF, AccessRecordStatus.ACTIVE, List.of(AdminRole.ANALYST),
                List.of(EVENT_OFF1));
        member("multi-off-member", TENANT_A, AccessRecordStatus.INACTIVE, List.of(AdminRole.ANALYST),
                List.of(EVENT_A1));
        member("multi-off-member", TENANT_B, AccessRecordStatus.ACTIVE, List.of(AdminRole.REVIEWER),
                List.of(EVENT_B1));
    }

    @Test
    void activeMembershipListsOnlyActiveGrantedEventsSortedByNameThenId() throws Exception {
        mvc.perform(list().header(HttpHeaders.AUTHORIZATION, bearer("analyst-a")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.pageSize").value(50))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].id").value(EVENT_A5_SAME_NAME.toString()))
                .andExpect(jsonPath("$.items[0].externalEventId").value("ext-a5"))
                .andExpect(jsonPath("$.items[0].name").value("가 공연"))
                .andExpect(jsonPath("$.items[1].id").value(EVENT_A2.toString()))
                .andExpect(jsonPath("$.items[2].id").value(EVENT_A1.toString()))
                .andExpect(content().string(not(containsString(EVENT_A3_INACTIVE.toString()))))
                .andExpect(content().string(not(containsString(EVENT_A4_UNGRANTED.toString()))))
                .andExpect(content().string(not(containsString(EVENT_B1.toString()))));
    }

    @Test
    void eventGrantsAreSeparatedPerMembershipInsideOneTenant() throws Exception {
        mvc.perform(list().header(HttpHeaders.AUTHORIZATION, bearer("reviewer-a1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].id").value(EVENT_A1.toString()));
    }

    @Test
    void everyExistingAdminRoleCanListItsGrantedEventsButMembersWithoutRoleGet403() throws Exception {
        for (String subject : List.of("approver-a", "tenant-admin-a")) {
            mvc.perform(list().header(HttpHeaders.AUTHORIZATION, bearer(subject)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalItems").value(1))
                    .andExpect(jsonPath("$.items[0].id").value(EVENT_A2.toString()));
        }
        mvc.perform(list().header(HttpHeaders.AUTHORIZATION, bearer("no-role-a")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(content().string(not(containsString("가 공연"))));
    }

    @Test
    void subjectsWithoutAccessibleEventsGetANormalEmptyList() throws Exception {
        for (String subject : List.of("no-grant-a", "inactive-member-a", "off-tenant-only", "unknown-subject")) {
            mvc.perform(list().header(HttpHeaders.AUTHORIZATION, bearer(subject)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(0))
                    .andExpect(jsonPath("$.totalItems").value(0))
                    .andExpect(jsonPath("$.hasNext").value(false));
        }
    }

    @Test
    void subjectWithSeveralActiveTenantsIsBlockedUntilTenantSelectionPolicyExists() throws Exception {
        mvc.perform(list().header(HttpHeaders.AUTHORIZATION, bearer("multi")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TENANT_SELECTION_REQUIRED"))
                .andExpect(content().string(not(containsString("고객사"))))
                .andExpect(content().string(not(containsString(TENANT_A.toString()))))
                .andExpect(content().string(not(containsString(TENANT_B.toString()))))
                .andExpect(content().string(not(containsString(EVENT_A1.toString()))))
                .andExpect(content().string(not(containsString(EVENT_B1.toString()))));
        // 클라이언트가 보낸 tenantId는 범위 선택이나 권한 근거로 사용하지 않습니다.
        mvc.perform(list().param("tenantId", TENANT_A.toString())
                        .header(HttpHeaders.AUTHORIZATION, bearer("multi")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TENANT_SELECTION_REQUIRED"));
    }

    @Test
    void inactiveTenantOrMembershipDoesNotCountAsASecondTenant() throws Exception {
        mvc.perform(list().header(HttpHeaders.AUTHORIZATION, bearer("multi-off-tenant")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].id").value(EVENT_A2.toString()));
        mvc.perform(list().header(HttpHeaders.AUTHORIZATION, bearer("multi-off-member")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].id").value(EVENT_B1.toString()))
                .andExpect(jsonPath("$.items[0].externalEventId").value("ext-a1"));
    }

    @Test
    void missingInvalidForeignOrExpiredTokensAreRejectedAs401() throws Exception {
        mvc.perform(list()).andExpect(status().isUnauthorized());
        mvc.perform(list().header(HttpHeaders.AUTHORIZATION, "Bearer broken"))
                .andExpect(status().isUnauthorized());
        Instant now = Instant.now();
        for (String token : List.of(
                token("analyst-a", "https://issuer.example.test/other", ADMIN_AUDIENCE, now.plusSeconds(300),
                        keyPair.getPrivate()),
                token("analyst-a", ADMIN_ISSUER, "ticket-defender-analysis", now.plusSeconds(300),
                        keyPair.getPrivate()),
                token("analyst-a", ADMIN_ISSUER, ADMIN_AUDIENCE, now.minusSeconds(600), keyPair.getPrivate()),
                token("analyst-a", ADMIN_ISSUER, ADMIN_AUDIENCE, now.plusSeconds(300),
                        otherKeyPair.getPrivate()))) {
            mvc.perform(list().header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().string(not(containsString("가 공연"))));
        }
    }

    @Test
    void pagingIsBoundedAndInvalidValuesAreRejected() throws Exception {
        mvc.perform(list().param("pageSize", "2").header(HttpHeaders.AUTHORIZATION, bearer("analyst-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[1].id").value(EVENT_A2.toString()));
        mvc.perform(list().param("page", "1").param("pageSize", "2")
                        .header(HttpHeaders.AUTHORIZATION, bearer("analyst-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(EVENT_A1.toString()));
        for (MockHttpServletRequestBuilder request : List.of(list().param("page", "-1"),
                list().param("pageSize", "0"), list().param("pageSize", "101"), list().param("page", "x"),
                list().param("page", String.valueOf(Integer.MAX_VALUE)).param("pageSize", "100"))) {
            mvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer("analyst-a")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }

    @Test
    void responseExposesOnlyMinimalEventMetadata() throws Exception {
        mvc.perform(list().header(HttpHeaders.AUTHORIZATION, bearer("analyst-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].tenantId").doesNotExist())
                .andExpect(jsonPath("$.items[0].status").doesNotExist())
                .andExpect(jsonPath("$.items[0].publicIntakeEnabled").doesNotExist())
                .andExpect(content().string(not(containsString(TENANT_A.toString()))))
                .andExpect(content().string(not(containsString("analyst-a"))))
                .andExpect(content().string(not(containsString("ANALYST"))))
                .andExpect(content().string(not(containsString("membership"))))
                .andExpect(content().string(not(containsString("고객사 A"))));
    }

    private void event(UUID id, UUID tenant, String externalRef, String name, AccessRecordStatus status) {
        events.save(new TenantEvent(id, tenant, externalRef, name, status));
    }

    private void member(String subject, UUID tenant, AccessRecordStatus status, List<AdminRole> memberRoles,
            List<UUID> eventIds) {
        UUID membership = UUID.randomUUID();
        memberships.save(new AdminMembershipEntity(membership, tenant, subject, status));
        memberRoles.forEach(role -> roles.save(new MembershipRole(UUID.randomUUID(), membership, role)));
        eventIds.forEach(event -> grants.save(new MembershipEventGrant(UUID.randomUUID(), membership, tenant, event)));
    }

    private static MockHttpServletRequestBuilder list() {
        return get("/api/v1/admin/events");
    }

    private static String bearer(String subject) throws Exception {
        return token(subject, ADMIN_ISSUER, ADMIN_AUDIENCE, Instant.now().plusSeconds(300), keyPair.getPrivate());
    }

    private static String token(String subject, String issuer, String audience, Instant expiresAt,
            PrivateKey key) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer(issuer)
                .audience(audience)
                .issueTime(Date.from(expiresAt.minusSeconds(600)))
                .expirationTime(Date.from(expiresAt))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner(key));
        return "Bearer " + jwt.serialize();
    }
}
