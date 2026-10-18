package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.AdminMembershipEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AdminMembershipRepository extends JpaRepository<AdminMembershipEntity, UUID> {
    Optional<AdminMembershipEntity> findBySubjectAndTenantId(String subject, UUID tenantId);
}
