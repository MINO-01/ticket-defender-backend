package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.EvidencePreservationPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EvidencePreservationPolicyRepository extends JpaRepository<EvidencePreservationPolicy, UUID> {
    Optional<EvidencePreservationPolicy> findTopByPolicyIdOrderByPolicyVersionDesc(UUID policyId);
    Optional<EvidencePreservationPolicy> findByPolicyIdAndPolicyVersion(UUID policyId, long policyVersion);
}
