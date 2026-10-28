package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.EvidencePreservationAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EvidencePreservationAttemptRepository extends JpaRepository<EvidencePreservationAttempt, UUID> {
    List<EvidencePreservationAttempt> findByCaseIdOrderByAttemptedAtAscIdAsc(UUID caseId);
}
