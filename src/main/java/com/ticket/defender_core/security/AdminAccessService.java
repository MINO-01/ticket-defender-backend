package com.ticket.defender_core.security;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminAccessService {

    private final AdminMembershipLookup memberships;
    private final EventScopeLookup events;

    /** For list/query filters: derive the tenant from the server's event lookup. */
    public AdminActor requireEventAccess(UUID eventId, Set<AdminRole> allowedRoles) {
        verifiedSubject();
        UUID tenantId = events.findTenantId(eventId).orElseThrow(AdminAccessService::notFound);
        return requireResourceAccess(new AdminResourceScope(tenantId, eventId), null, allowedRoles);
    }

    /**
     * Call after loading the resource's stored scope. A client-selected tenant or event may only
     * narrow the request; it cannot establish ownership or membership.
     */
    public AdminActor requireResourceAccess(AdminResourceScope storedScope,
            AdminResourceScope requestedScope, Set<AdminRole> allowedRoles) {
        String subject = verifiedSubject();
        if (storedScope == null || storedScope.tenantId() == null || storedScope.eventId() == null
                || (requestedScope != null && !storedScope.equals(requestedScope))) {
            throw notFound();
        }
        UUID actualTenant = events.findTenantId(storedScope.eventId()).orElseThrow(AdminAccessService::notFound);
        if (!storedScope.tenantId().equals(actualTenant)) {
            throw notFound();
        }

        AdminMembership membership = memberships.findActiveMembership(subject, actualTenant)
                .orElseThrow(AdminAccessService::notFound);
        if (!subject.equals(membership.subject()) || !actualTenant.equals(membership.tenantId())
                || !membership.eventIds().contains(storedScope.eventId())) {
            throw notFound();
        }
        // 권한 없는 고객사와 공연은 위에서 404로 숨긴 뒤에만 확인하므로 409가 다른 고객사 자원의 존재를 드러내지 않습니다.
        // 고객사 선택 정책(DEC-02) 확정 전에는 여러 고객사 소속 관리자의 업무를 임의 고객사로 처리하지 않습니다.
        if (memberships.hasActiveMembershipsInMultipleTenants(subject)) {
            throw new TenantSelectionRequiredException();
        }
        if (allowedRoles == null || allowedRoles.isEmpty()) {
            throw new IllegalArgumentException("At least one role is required");
        }
        if (membership.roles().stream().noneMatch(allowedRoles::contains)) {
            throw new AccessDeniedException("Required role is missing");
        }
        return new AdminActor(subject, actualTenant, storedScope.eventId());
    }

    /** 서명과 issuer, audience, 만료 검증을 통과한 관리자 JWT의 subject만 반환합니다. */
    public String authenticatedSubject() {
        return verifiedSubject();
    }

    private static String verifiedSubject() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtToken) || !authentication.isAuthenticated()
                || jwtToken.getToken().getSubject() == null || jwtToken.getToken().getSubject().isBlank()) {
            throw new InsufficientAuthenticationException("Verified administrator identity is required");
        }
        return jwtToken.getToken().getSubject();
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found");
    }
}
