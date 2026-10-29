package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.ApprovalRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, UUID> {
    interface AccessScope {
        UUID getId();
        UUID getTenantId();
        UUID getEventId();
        UUID getCaseId();
    }

    @Query("select request.id as id, request.tenantId as tenantId, request.eventId as eventId, "
            + "request.caseId as caseId from ApprovalRequest request where request.id = :id")
    Optional<AccessScope> findAccessScopeById(UUID id);

    /** Reads only the lock target so a stale ApprovalRequest never enters the write transaction. */
    @Query("select request.caseId from ApprovalRequest request where request.id = :id")
    Optional<UUID> findCaseIdById(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from ApprovalRequest request where request.id = :id")
    Optional<ApprovalRequest> findByIdForUpdate(UUID id);

    Optional<ApprovalRequest> findByTenantIdAndCaseIdAndStatus(
            UUID tenantId, UUID caseId, ApprovalRequest.Status status);
    Optional<ApprovalRequest> findByReportVersionId(UUID reportVersionId);
}
