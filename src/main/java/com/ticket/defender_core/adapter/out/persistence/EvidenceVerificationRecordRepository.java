package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.EvidenceVerificationRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EvidenceVerificationRecordRepository extends JpaRepository<EvidenceVerificationRecord, UUID> {
    Page<EvidenceVerificationRecord> findByCaseId(UUID caseId, Pageable pageable);
    Optional<EvidenceVerificationRecord> findTopByEvidenceSnapshotIdAndSourceReferenceIdOrderByVerifiedAtDescIdDesc(
            UUID snapshotId, UUID sourceReferenceId);
}
