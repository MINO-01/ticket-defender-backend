package com.ticket.defender_core.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AdminAccessServiceTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID EVENT = UUID.fromString("33333333-3333-4333-8333-333333333333");

    @Mock
    private AdminMembershipLookup memberships;

    @Mock
    private EventScopeLookup events;

    @AfterEach
    void clearIdentity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void eventAuthorizationUsesStoredTenantAndVerifiedSubject() {
        authenticate();
        given(events.findTenantId(EVENT)).willReturn(Optional.of(TENANT));
        given(memberships.findActiveMembership("admin-1", TENANT)).willReturn(Optional.of(
                new AdminMembership("admin-1", TENANT, Set.of(AdminRole.REVIEWER), Set.of(EVENT))));

        AdminActor actor = new AdminAccessService(memberships, events)
                .requireEventAccess(EVENT, Set.of(AdminRole.REVIEWER));

        assertThat(actor).isEqualTo(new AdminActor("admin-1", TENANT, EVENT));
    }

    @Test
    void eventAndResourceTenantMismatchAreHidden() {
        authenticate();
        given(events.findTenantId(EVENT)).willReturn(Optional.of(TENANT));
        AdminAccessService access = new AdminAccessService(memberships, events);

        assertThatThrownBy(() -> access.requireResourceAccess(
                new AdminResourceScope(OTHER_TENANT, EVENT), null, Set.of(AdminRole.ANALYST)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(memberships);
    }

    @Test
    void multiTenantSubjectIsBlockedAfterScopeIsVerifiedAndBeforeRoleCheck() {
        authenticate();
        given(events.findTenantId(EVENT)).willReturn(Optional.of(TENANT));
        given(memberships.findActiveMembership("admin-1", TENANT)).willReturn(Optional.of(
                new AdminMembership("admin-1", TENANT, Set.of(AdminRole.REVIEWER), Set.of(EVENT))));
        given(memberships.hasActiveMembershipsInMultipleTenants("admin-1")).willReturn(true);
        AdminAccessService access = new AdminAccessService(memberships, events);

        assertThatThrownBy(() -> access.requireEventAccess(EVENT, Set.of(AdminRole.REVIEWER)))
                .isInstanceOf(TenantSelectionRequiredException.class);
        // 역할이 없어도 403보다 임시 차단 409가 먼저 적용되어 고객사 선택 전 업무를 막습니다.
        assertThatThrownBy(() -> access.requireEventAccess(EVENT, Set.of(AdminRole.APPROVER)))
                .isInstanceOf(TenantSelectionRequiredException.class);
    }

    @Test
    void multiTenantCheckRunsOnlyAfterHiddenScopesAreRejectedAs404() {
        authenticate();
        given(events.findTenantId(EVENT)).willReturn(Optional.of(TENANT));
        given(memberships.findActiveMembership("admin-1", TENANT)).willReturn(Optional.empty());
        AdminAccessService access = new AdminAccessService(memberships, events);

        assertThatThrownBy(() -> access.requireEventAccess(EVENT, Set.of(AdminRole.REVIEWER)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(memberships, never()).hasActiveMembershipsInMultipleTenants(anyString());
    }

    @Test
    void missingAuthenticationIsRejectedBeforeScopeLookup() {
        assertThatThrownBy(() -> new AdminAccessService(memberships, events)
                .requireEventAccess(EVENT, Set.of(AdminRole.ANALYST)))
                .isInstanceOf(InsufficientAuthenticationException.class);
        verifyNoInteractions(events, memberships);
    }

    @Test
    void eventDatabaseFailureCannotGrantAccess() {
        authenticate();
        given(events.findTenantId(EVENT)).willThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThatThrownBy(() -> new AdminAccessService(memberships, events)
                .requireEventAccess(EVENT, Set.of(AdminRole.ANALYST)))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verifyNoInteractions(memberships);
    }

    @Test
    void membershipDatabaseFailureCannotGrantAccess() {
        authenticate();
        given(events.findTenantId(EVENT)).willReturn(Optional.of(TENANT));
        given(memberships.findActiveMembership("admin-1", TENANT))
                .willThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThatThrownBy(() -> new AdminAccessService(memberships, events)
                .requireEventAccess(EVENT, Set.of(AdminRole.ANALYST)))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    private void authenticate() {
        Instant now = Instant.now();
        Jwt jwt = new Jwt("signed-test-token", now, now.plusSeconds(300),
                Map.of("alg", "RS256"), Map.of("sub", "admin-1",
                "roles", Set.of("TENANT_ADMIN"), "tenantId", OTHER_TENANT.toString()));
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt);
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
