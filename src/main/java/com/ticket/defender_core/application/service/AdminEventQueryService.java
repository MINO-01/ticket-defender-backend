package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.AdminEventDto;
import com.ticket.defender_core.adapter.out.persistence.AdminMembershipRepository;
import com.ticket.defender_core.adapter.out.persistence.MembershipRoleRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantEventRepository;
import com.ticket.defender_core.domain.AdminMembershipEntity;
import com.ticket.defender_core.security.AdminAccessService;
import com.ticket.defender_core.security.AdminRole;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * 검증된 관리자 JWT subject의 활성 멤버십에서 공연 권한이 부여된 활성 공연 목록을 조회합니다.
 * 목록에 공연이 보인다는 것은 해당 공연의 개별 업무 API 실행 권한을 뜻하지 않으며, 각 API가 역할을 다시 검사합니다.
 */
@Service
@RequiredArgsConstructor
public class AdminEventQueryService {
    /** 현재 정의된 관리자 역할 중 하나라도 있으면 공연 선택 목록을 볼 수 있습니다. */
    private static final Set<AdminRole> LIST_ROLES = Set.of(
            AdminRole.TENANT_ADMIN, AdminRole.ANALYST, AdminRole.REVIEWER, AdminRole.APPROVER);
    private static final int MAX_PAGE_SIZE = 100;
    /** 고객사가 하나인지 여러 개인지만 판단하면 되므로 2건까지만 읽습니다. */
    private static final PageRequest TENANT_PROBE = PageRequest.of(0, 2);

    private final AdminAccessService access;
    private final AdminMembershipRepository memberships;
    private final MembershipRoleRepository roles;
    private final TenantEventRepository events;

    @Transactional(readOnly = true)
    public AdminEventDto.Page list(int page, int pageSize) {
        if (page < 0 || pageSize < 1 || pageSize > MAX_PAGE_SIZE || (long) page * pageSize > Integer.MAX_VALUE)
            throw new CaseApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        String subject = access.authenticatedSubject();
        List<AdminMembershipEntity> active = memberships.findActiveBySubject(subject, TENANT_PROBE);
        if (active.isEmpty()) return new AdminEventDto.Page(List.of(), page, pageSize, 0, false);
        // 고객사 선택 정책(DEC-02)이 확정되기 전에는 여러 고객사 범위를 합치거나 임의로 고르지 않습니다.
        if (active.size() > 1) throw new CaseApiException(HttpStatus.CONFLICT, "TENANT_SELECTION_REQUIRED");
        AdminMembershipEntity membership = active.getFirst();
        boolean hasRole = roles.findAllByMembershipId(membership.getId()).stream()
                .map(role -> Arrays.stream(AdminRole.values()).filter(known -> known.name().equals(role.getRole()))
                        .findFirst().orElse(null))
                .anyMatch(LIST_ROLES::contains);
        if (!hasRole) throw new AccessDeniedException("Required role is missing");
        var found = events.findGrantedActiveEvents(membership.getTenantId(), membership.getId(),
                PageRequest.of(page, pageSize));
        return new AdminEventDto.Page(found.stream()
                .map(event -> new AdminEventDto.Event(event.getId(), event.getExternalEventRef(), event.getName()))
                .toList(), page, pageSize, found.getTotalElements(), found.hasNext());
    }
}
