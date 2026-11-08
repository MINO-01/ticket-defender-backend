package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.ApprovalAuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

public interface ApprovalAuditEventRepository extends JpaRepository<ApprovalAuditEvent, UUID> {
    List<ApprovalAuditEvent> findByApprovalRequestIdOrderByOccurredAtAscIdAsc(UUID approvalRequestId);
    Page<ApprovalAuditEvent> findByCaseId(UUID caseId, Pageable pageable);
}
