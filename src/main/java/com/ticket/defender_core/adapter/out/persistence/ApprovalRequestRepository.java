package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.ApprovalRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, UUID> {
    Optional<ApprovalRequest> findByTenantIdAndCaseIdAndStatus(
            UUID tenantId, UUID caseId, ApprovalRequest.Status status);
    Optional<ApprovalRequest> findByReportVersionId(UUID reportVersionId);
}
