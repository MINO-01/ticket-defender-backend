package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.CaseIdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CaseIdempotencyRepository extends JpaRepository<CaseIdempotencyRecord, UUID> {
    Optional<CaseIdempotencyRecord> findByTenantIdAndActorMembershipIdAndOperationAndTargetCaseIdAndKeyDigest(
            UUID tenantId, UUID actorMembershipId, String operation, UUID targetCaseId, String keyDigest);
}
