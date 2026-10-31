package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.OfficialPdfIssuance;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OfficialPdfIssuanceRepository extends JpaRepository<OfficialPdfIssuance, UUID> {
    interface AccessScope {
        UUID getId();
        UUID getTenantId();
        UUID getEventId();
        UUID getCaseId();
    }

    @Query("select issuance.id as id, issuance.tenantId as tenantId, issuance.eventId as eventId, "
            + "issuance.caseId as caseId from OfficialPdfIssuance issuance where issuance.id = :id")
    Optional<AccessScope> findAccessScopeById(UUID id);

    @Query("select issuance.caseId from OfficialPdfIssuance issuance where issuance.id = :id")
    Optional<UUID> findCaseIdById(UUID id);

    Optional<OfficialPdfIssuance> findByApprovalDecisionId(UUID approvalDecisionId);
    List<OfficialPdfIssuance> findByReportVersionIdOrderByRequestedAtDesc(UUID reportVersionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select issuance from OfficialPdfIssuance issuance where issuance.id = :id")
    Optional<OfficialPdfIssuance> findByIdForUpdate(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select issuance from OfficialPdfIssuance issuance where issuance.approvalDecisionId = :approvalDecisionId")
    Optional<OfficialPdfIssuance> findByApprovalDecisionIdForUpdate(UUID approvalDecisionId);
}
