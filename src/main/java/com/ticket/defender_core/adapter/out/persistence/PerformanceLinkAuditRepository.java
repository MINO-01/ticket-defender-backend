package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.PerformanceLinkAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PerformanceLinkAuditRepository extends JpaRepository<PerformanceLinkAudit, UUID> {
    Optional<PerformanceLinkAudit> findByActorMembershipIdAndFanReportIdAndIdempotencyKeyDigest(
            UUID actorMembershipId, UUID fanReportId, String keyDigest);
}
