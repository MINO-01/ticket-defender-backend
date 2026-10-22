package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.Case;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

public interface CaseRepository extends JpaRepository<Case, UUID> {
    @Query("""
            select c from Case c where c.tenantId = :tenantId and c.eventId = :eventId
              and (:status is null or c.status = :status)
              and (:membershipId is null or c.createdByMembershipId = :membershipId
                   or c.assigneeMembershipId = :membershipId)
            """)
    Page<Case> findVisible(UUID tenantId, UUID eventId, Case.Status status,
                           UUID membershipId, Pageable pageable);
}
