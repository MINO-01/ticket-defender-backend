package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.persistence.AdminMembershipRepository;
import com.ticket.defender_core.adapter.out.persistence.MembershipEventGrantRepository;
import com.ticket.defender_core.adapter.out.persistence.MembershipRoleRepository;
import com.ticket.defender_core.domain.AccessRecordStatus;
import com.ticket.defender_core.domain.AdminMembershipEntity;
import com.ticket.defender_core.domain.MembershipEventGrant;
import com.ticket.defender_core.domain.MembershipRole;
import com.ticket.defender_core.security.AdminActor;
import com.ticket.defender_core.security.AdminRole;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CaseMembershipAccess {
    private final AdminMembershipRepository memberships;
    private final MembershipRoleRepository roles;
    private final MembershipEventGrantRepository grants;

    public AdminMembershipEntity actor(AdminActor actor) {
        return memberships.findBySubjectAndTenantId(actor.subject(), actor.tenantId())
                .filter(member -> AccessRecordStatus.ACTIVE.name().equals(member.getStatus()))
                .orElseThrow(CaseMembershipAccess::notFound);
    }

    public Set<AdminRole> roles(UUID membershipId) {
        return roles.findAllByMembershipId(membershipId).stream()
                .map(MembershipRole::getRole).map(AdminRole::valueOf)
                .collect(Collectors.toUnmodifiableSet());
    }

    public AdminMembershipEntity requireAssignable(UUID membershipId, UUID tenantId, UUID eventId) {
        AdminMembershipEntity member = memberships.findById(membershipId)
                .filter(found -> tenantId.equals(found.getTenantId())
                        && AccessRecordStatus.ACTIVE.name().equals(found.getStatus()))
                .orElseThrow(CaseMembershipAccess::notFound);
        if (grants.findAllByMembershipIdAndTenantId(membershipId, tenantId).stream()
                .map(MembershipEventGrant::getEventId).noneMatch(eventId::equals)) throw notFound();
        Set<AdminRole> memberRoles = roles(membershipId);
        if (!memberRoles.contains(AdminRole.ANALYST) && !memberRoles.contains(AdminRole.REVIEWER))
            throw new CaseApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ASSIGNEE_ROLE_INVALID");
        return member;
    }

    private static CaseApiException notFound() {
        return new CaseApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }
}
