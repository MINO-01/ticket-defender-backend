package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.CaseSourceReference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CaseSourceReferenceRepository extends JpaRepository<CaseSourceReference, UUID> {
    List<CaseSourceReference> findByCaseIdOrderByLinkedAtAscIdAsc(UUID caseId);
    List<CaseSourceReference> findByTenantIdAndEventIdAndSourceKeyDigest(
            UUID tenantId, UUID eventId, String sourceKeyDigest);
}
