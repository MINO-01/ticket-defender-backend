package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.persistence.AdminMembershipRepository;
import com.ticket.defender_core.adapter.out.persistence.MembershipEventGrantRepository;
import com.ticket.defender_core.adapter.out.persistence.MembershipRoleRepository;
import com.ticket.defender_core.domain.AccessRecordStatus;
import com.ticket.defender_core.domain.ApprovalRequest;
import com.ticket.defender_core.domain.Case;
import com.ticket.defender_core.security.AdminRole;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 승인 대기 중 요청자의 REVIEWER 권한이 그대로 유지되는지 확인한다. 멤버십, 역할, 이벤트 권한은 앱 밖에서
 * 바뀔 수 있으므로 Receipt 발급과 결정 시점에 다시 확인하고, 철회되었으면 자동 무효화 대상으로 본다.
 */
@Component
@RequiredArgsConstructor
public class ApprovalRequesterAccessValidator {
    public static final String REVOKED = "APPROVAL_REQUESTER_ACCESS_REVOKED";

    private final AdminMembershipRepository memberships;
    private final MembershipRoleRepository roles;
    private final MembershipEventGrantRepository grants;

    public void requireCurrent(Case target, ApprovalRequest request) {
        boolean current = memberships.findById(request.getRequestedByMembershipId())
                .filter(member -> target.getTenantId().equals(member.getTenantId())
                        && AccessRecordStatus.ACTIVE.name().equals(member.getStatus()))
                .isPresent()
                && roles.findAllByMembershipId(request.getRequestedByMembershipId()).stream()
                        .anyMatch(role -> AdminRole.REVIEWER.name().equals(role.getRole()))
                && grants.findAllByMembershipIdAndTenantId(request.getRequestedByMembershipId(),
                        target.getTenantId()).stream()
                        .anyMatch(grant -> target.getEventId().equals(grant.getEventId()));
        if (!current) throw new CaseApiException(HttpStatus.CONFLICT, REVOKED);
    }
}
