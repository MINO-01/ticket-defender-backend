package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.MembershipEventGrant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MembershipEventGrantRepository extends JpaRepository<MembershipEventGrant, UUID> {
    List<MembershipEventGrant> findAllByMembershipIdAndTenantId(UUID membershipId, UUID tenantId);
}
