package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.AccessRecordStatus;
import com.ticket.defender_core.domain.AdminMembershipEntity;
import com.ticket.defender_core.domain.MembershipEventGrant;
import com.ticket.defender_core.domain.MembershipRole;
import com.ticket.defender_core.domain.Tenant;
import com.ticket.defender_core.domain.TenantEvent;
import com.ticket.defender_core.security.AdminAccessService;
import com.ticket.defender_core.security.AdminActor;
import com.ticket.defender_core.security.AdminMembershipLookup;
import com.ticket.defender_core.security.AdminResourceScope;
import com.ticket.defender_core.security.AdminRole;
import com.ticket.defender_core.security.EventReferenceLookup;
import com.ticket.defender_core.security.EventScopeLookup;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import({JpaAdminMembershipLookup.class, JpaEventScopeLookup.class, AdminAccessService.class})
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:admin-access-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"
})
class JpaAdminAccessIntegrationTest {
    private static final UUID TENANT_A = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID EVENT_A = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID EVENT_B = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID EVENT_A_OTHER = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID MEMBERSHIP_A = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID MEMBERSHIP_B = UUID.fromString("77777777-7777-4777-8777-777777777777");
    private static final String EXTERNAL_EVENT_REF = "festival/2026:opening";

    @Autowired private TenantRepository tenants;
    @Autowired private TenantEventRepository events;
    @Autowired private AdminMembershipRepository memberships;
    @Autowired private MembershipRoleRepository roles;
    @Autowired private MembershipEventGrantRepository eventGrants;
    @Autowired private AdminMembershipLookup membershipLookup;
    @Autowired private EventScopeLookup eventScopeLookup;
    @Autowired private EventReferenceLookup eventReferenceLookup;
    @Autowired private AdminAccessService access;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        tenants.save(new Tenant(TENANT_A, "고객사 A", AccessRecordStatus.ACTIVE));
        tenants.save(new Tenant(TENANT_B, "고객사 B", AccessRecordStatus.ACTIVE));
        events.save(new TenantEvent(EVENT_A, TENANT_A, EXTERNAL_EVENT_REF, "공연 A", AccessRecordStatus.ACTIVE));
        events.save(new TenantEvent(EVENT_B, TENANT_B, EXTERNAL_EVENT_REF, "공연 B", AccessRecordStatus.ACTIVE));
        events.save(new TenantEvent(EVENT_A_OTHER, TENANT_A, "other-event", "다른 공연", AccessRecordStatus.ACTIVE));
        memberships.save(new AdminMembershipEntity(MEMBERSHIP_A, TENANT_A, "admin-1", AccessRecordStatus.ACTIVE));
        memberships.save(new AdminMembershipEntity(MEMBERSHIP_B, TENANT_B, "admin-2", AccessRecordStatus.ACTIVE));
        roles.save(new MembershipRole(UUID.randomUUID(), MEMBERSHIP_A, AdminRole.ANALYST));
        roles.save(new MembershipRole(UUID.randomUUID(), MEMBERSHIP_B, AdminRole.REVIEWER));
        eventGrants.save(new MembershipEventGrant(UUID.randomUUID(), MEMBERSHIP_A, TENANT_A, EVENT_A));
        eventGrants.save(new MembershipEventGrant(UUID.randomUUID(), MEMBERSHIP_B, TENANT_B, EVENT_B));
    }

    @AfterEach
    void clearIdentity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void mapsSameExternalEventReferenceWithinEachTenant() {
        assertThat(eventReferenceLookup.findActiveEventId(TENANT_A, EXTERNAL_EVENT_REF)).contains(EVENT_A);
        assertThat(eventReferenceLookup.findActiveEventId(TENANT_B, EXTERNAL_EVENT_REF)).contains(EVENT_B);
        assertThat(eventReferenceLookup.findActiveEventId(TENANT_A, "missing")).isEmpty();
        assertThat(eventReferenceLookup.findActiveEventId(TENANT_A, EXTERNAL_EVENT_REF.toUpperCase())).isEmpty();
        assertThat(eventScopeLookup.findTenantId(EVENT_A)).contains(TENANT_A);
        assertThat(eventScopeLookup.findTenantId(EVENT_B)).contains(TENANT_B);
    }

    @Test
    void usesVerifiedSubjectAndDatabaseGrantsInsteadOfJwtClaims() {
        authenticate("admin-1");
        assertThat(membershipLookup.findActiveMembership("admin-1", TENANT_A))
                .get().extracting(m -> m.roles()).isEqualTo(Set.of(AdminRole.ANALYST));
        assertThat(access.requireEventAccess(EVENT_A, Set.of(AdminRole.ANALYST)))
                .isEqualTo(new AdminActor("admin-1", TENANT_A, EVENT_A));
        assertThat(access.requireResourceAccess(new AdminResourceScope(TENANT_A, EVENT_A), null,
                Set.of(AdminRole.ANALYST)))
                .isEqualTo(new AdminActor("admin-1", TENANT_A, EVENT_A));
        assertThatThrownBy(() -> access.requireEventAccess(EVENT_A, Set.of(AdminRole.TENANT_ADMIN)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void rejectsOtherTenantOtherEventAndForgedStoredScope() {
        authenticate("admin-1");
        assertNotFound(() -> access.requireResourceAccess(new AdminResourceScope(TENANT_B, EVENT_B), null,
                Set.of(AdminRole.ANALYST)));
        assertNotFound(() -> access.requireEventAccess(EVENT_A_OTHER, Set.of(AdminRole.ANALYST)));
        assertNotFound(() -> access.requireResourceAccess(new AdminResourceScope(TENANT_B, EVENT_A), null,
                Set.of(AdminRole.ANALYST)));
        assertNotFound(() -> access.requireResourceAccess(new AdminResourceScope(TENANT_A, EVENT_A),
                new AdminResourceScope(TENANT_B, EVENT_A), Set.of(AdminRole.ANALYST)));
    }

    @Test
    void rejectsMissingOrInactiveMembershipAndEvent() {
        authenticate("unknown");
        assertNotFound(() -> access.requireEventAccess(EVENT_A, Set.of(AdminRole.ANALYST)));
        assertThat(membershipLookup.findActiveMembership("Admin-1", TENANT_A)).isEmpty();

        UUID disabledMembership = UUID.randomUUID();
        memberships.save(new AdminMembershipEntity(disabledMembership, TENANT_A, "disabled", AccessRecordStatus.INACTIVE));
        roles.save(new MembershipRole(UUID.randomUUID(), disabledMembership, AdminRole.ANALYST));
        eventGrants.save(new MembershipEventGrant(UUID.randomUUID(), disabledMembership, TENANT_A, EVENT_A));
        authenticate("disabled");
        assertNotFound(() -> access.requireEventAccess(EVENT_A, Set.of(AdminRole.ANALYST)));

        UUID inactiveEvent = UUID.randomUUID();
        events.save(new TenantEvent(inactiveEvent, TENANT_A, "inactive", "중단 공연", AccessRecordStatus.INACTIVE));
        eventGrants.save(new MembershipEventGrant(UUID.randomUUID(), MEMBERSHIP_A, TENANT_A, inactiveEvent));
        authenticate("admin-1");
        assertNotFound(() -> access.requireEventAccess(inactiveEvent, Set.of(AdminRole.ANALYST)));
        assertThat(eventReferenceLookup.findActiveEventId(TENANT_A, "inactive")).isEmpty();
    }

    @Test
    void inactiveTenantHasNoAccessibleMembershipOrEvent() {
        UUID tenantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        tenants.save(new Tenant(tenantId, "중단 고객사", AccessRecordStatus.INACTIVE));
        events.save(new TenantEvent(eventId, tenantId, "disabled-event", "중단 공연", AccessRecordStatus.ACTIVE));
        memberships.save(new AdminMembershipEntity(membershipId, tenantId, "admin-3", AccessRecordStatus.ACTIVE));
        roles.save(new MembershipRole(UUID.randomUUID(), membershipId, AdminRole.ANALYST));
        eventGrants.save(new MembershipEventGrant(UUID.randomUUID(), membershipId, tenantId, eventId));

        assertThat(eventScopeLookup.findTenantId(eventId)).isEmpty();
        assertThat(eventReferenceLookup.findActiveEventId(tenantId, "disabled-event")).isEmpty();
        assertThat(membershipLookup.findActiveMembership("admin-3", tenantId)).isEmpty();
    }

    @Test
    void noncanonicalMembershipStatusCannotGrantAccess() {
        entityManager.flush();
        jdbc.update("UPDATE memberships SET status = ? WHERE id = ?", "active", MEMBERSHIP_A.toString());
        entityManager.clear();
        authenticate("admin-1");

        assertNotFound(() -> access.requireEventAccess(EVENT_A, Set.of(AdminRole.ANALYST)));
    }

    @Test
    void rolesAndEventGrantsStayWithinEachMembership() {
        UUID secondMembership = UUID.randomUUID();
        memberships.save(new AdminMembershipEntity(secondMembership, TENANT_B, "admin-1", AccessRecordStatus.ACTIVE));
        roles.save(new MembershipRole(UUID.randomUUID(), secondMembership, AdminRole.REVIEWER));
        eventGrants.save(new MembershipEventGrant(UUID.randomUUID(), secondMembership, TENANT_B, EVENT_B));
        authenticate("admin-1");
        assertThat(membershipLookup.findActiveMembership("admin-1", TENANT_B))
                .get().extracting(m -> m.roles()).isEqualTo(Set.of(AdminRole.REVIEWER));
        assertThat(access.requireEventAccess(EVENT_B, Set.of(AdminRole.REVIEWER)).tenantId()).isEqualTo(TENANT_B);
        assertThatThrownBy(() -> access.requireEventAccess(EVENT_B, Set.of(AdminRole.ANALYST)))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(access.requireEventAccess(EVENT_A, Set.of(AdminRole.ANALYST)).tenantId()).isEqualTo(TENANT_A);
    }

    @Test
    void supportsMultipleRolesAndEventsWithinOneMembership() {
        roles.save(new MembershipRole(UUID.randomUUID(), MEMBERSHIP_A, AdminRole.REVIEWER));
        eventGrants.save(new MembershipEventGrant(UUID.randomUUID(), MEMBERSHIP_A, TENANT_A, EVENT_A_OTHER));
        authenticate("admin-1");

        assertThat(membershipLookup.findActiveMembership("admin-1", TENANT_A))
                .get().extracting(m -> m.roles()).isEqualTo(Set.of(AdminRole.ANALYST, AdminRole.REVIEWER));
        assertThat(access.requireEventAccess(EVENT_A_OTHER, Set.of(AdminRole.REVIEWER)).eventId())
                .isEqualTo(EVENT_A_OTHER);
    }

    @Test
    void rejectsDuplicateExternalEventReferenceWithinOneTenant() {
        assertThatThrownBy(() -> events.saveAndFlush(new TenantEvent(UUID.randomUUID(), TENANT_A,
                EXTERNAL_EVENT_REF, "중복 공연", AccessRecordStatus.ACTIVE)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static void assertNotFound(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    private static void authenticate(String subject) {
        Instant now = Instant.now();
        Jwt jwt = new Jwt("signed-test-token", now, now.plusSeconds(300), Map.of("alg", "RS256"),
                Map.of("sub", subject, "roles", Set.of("TENANT_ADMIN"),
                        "tenantId", TENANT_B.toString(), "eventIds", Set.of(EVENT_B.toString())));
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt);
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
