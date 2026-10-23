package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.EvidenceSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EvidenceSnapshotRepository extends JpaRepository<EvidenceSnapshot, UUID> {
    Optional<EvidenceSnapshot> findByCaseIdAndEvidenceRevision(UUID caseId, long evidenceRevision);
    List<EvidenceSnapshot> findByCaseIdOrderByEvidenceRevisionDesc(UUID caseId);
    Page<EvidenceSnapshot> findByCaseId(UUID caseId, Pageable pageable);
}
