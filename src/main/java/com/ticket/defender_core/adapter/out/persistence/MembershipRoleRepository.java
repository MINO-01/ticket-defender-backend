package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.MembershipRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MembershipRoleRepository extends JpaRepository<MembershipRole, UUID> {
    List<MembershipRole> findAllByMembershipId(UUID membershipId);
}
