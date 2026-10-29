package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.ApprovalRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from ApprovalRequest request where request.id = :id")
    Optional<ApprovalRequest> findByIdForUpdate(UUID id);

    Optional<ApprovalRequest> findByTenantIdAndCaseIdAndStatus(
            UUID tenantId, UUID caseId, ApprovalRequest.Status status);
    Optional<ApprovalRequest> findByReportVersionId(UUID reportVersionId);
}
