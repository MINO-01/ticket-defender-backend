package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.ApprovalDecision;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface ApprovalDecisionRepository extends JpaRepository<ApprovalDecision, UUID> {
    Optional<ApprovalDecision> findByApprovalRequestId(UUID approvalRequestId);

    @Query("select decision.caseId from ApprovalDecision decision where decision.id = :id")
    Optional<UUID> findCaseIdById(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select decision from ApprovalDecision decision where decision.id = :id")
    Optional<ApprovalDecision> findByIdForUpdate(UUID id);
}
