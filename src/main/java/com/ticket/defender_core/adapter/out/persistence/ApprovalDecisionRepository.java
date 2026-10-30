package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.ApprovalDecision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ApprovalDecisionRepository extends JpaRepository<ApprovalDecision, UUID> {
    Optional<ApprovalDecision> findByApprovalRequestId(UUID approvalRequestId);
}
