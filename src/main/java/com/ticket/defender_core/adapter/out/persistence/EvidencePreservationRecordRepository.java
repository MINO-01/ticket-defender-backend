package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.EvidencePreservationRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EvidencePreservationRecordRepository extends JpaRepository<EvidencePreservationRecord, UUID> {
    List<EvidencePreservationRecord> findByCaseIdOrderByVerifiedAtAscIdAsc(UUID caseId);

    Optional<EvidencePreservationRecord> findByTenantIdAndEvidenceIdAndStorageProfileAndActualVersionIdAndPreservationPolicyIdAndPreservationPolicyVersion(
            UUID tenantId, UUID evidenceId, String storageProfile, String actualVersionId,
            UUID policyId, long policyVersion);
}
