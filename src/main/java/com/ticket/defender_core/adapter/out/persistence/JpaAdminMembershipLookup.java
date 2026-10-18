package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.AccessRecordStatus;
import com.ticket.defender_core.domain.MembershipEventGrant;
import com.ticket.defender_core.security.AdminMembership;
import com.ticket.defender_core.security.AdminMembershipLookup;
import com.ticket.defender_core.security.AdminRole;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class JpaAdminMembershipLookup implements AdminMembershipLookup {
    private final TenantRepository tenants;
    private final AdminMembershipRepository memberships;
    private final MembershipRoleRepository roles;
    private final MembershipEventGrantRepository eventGrants;

    public JpaAdminMembershipLookup(TenantRepository tenants, AdminMembershipRepository memberships,
            MembershipRoleRepository roles, MembershipEventGrantRepository eventGrants) {
        this.tenants = tenants;
        this.memberships = memberships;
        this.roles = roles;
        this.eventGrants = eventGrants;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AdminMembership> findActiveMembership(String subject, UUID tenantId) {
        if (tenants.findById(tenantId)
                .filter(tenant -> AccessRecordStatus.ACTIVE.name().equals(tenant.getStatus())).isEmpty()) {
            return Optional.empty();
        }
        return memberships.findBySubjectAndTenantId(subject, tenantId)
                .filter(membership -> subject.equals(membership.getSubject())
                        && AccessRecordStatus.ACTIVE.name().equals(membership.getStatus()))
                .map(membership -> new AdminMembership(
                        membership.getSubject(),
                        membership.getTenantId(),
                        roles.findAllByMembershipId(membership.getId()).stream()
                                .map(role -> AdminRole.valueOf(role.getRole()))
                                .collect(Collectors.toUnmodifiableSet()),
                        eventGrants.findAllByMembershipIdAndTenantId(membership.getId(), tenantId).stream()
                                .map(MembershipEventGrant::getEventId)
                                .collect(Collectors.toUnmodifiableSet())));
    }
}
