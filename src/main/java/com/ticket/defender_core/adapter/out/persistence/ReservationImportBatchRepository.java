package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.ReservationImportBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ReservationImportBatchRepository extends JpaRepository<ReservationImportBatch, UUID> {
    Optional<ReservationImportBatch> findByCreatedByMembershipIdAndEventIdAndIdempotencyKeyDigest(
            UUID membershipId, UUID eventId, String keyDigest);

    Optional<ReservationImportBatch> findByTenantIdAndEventIdAndPerformanceIdAndSourceVersion(
            UUID tenantId, UUID eventId, UUID performanceId, String sourceVersion);

    org.springframework.data.domain.Page<ReservationImportBatch> findByTenantIdAndEventId(java.util.UUID tenantId, java.util.UUID eventId, org.springframework.data.domain.Pageable pageable);
}
