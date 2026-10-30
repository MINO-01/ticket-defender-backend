package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.OfficialPdfArtifact;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OfficialPdfArtifactRepository extends JpaRepository<OfficialPdfArtifact, UUID> {
    Optional<OfficialPdfArtifact> findByIssuanceId(UUID issuanceId);
    Optional<OfficialPdfArtifact> findByApprovalDecisionId(UUID approvalDecisionId);
    List<OfficialPdfArtifact> findByReportVersionIdOrderByGeneratedAtDesc(UUID reportVersionId);
}
