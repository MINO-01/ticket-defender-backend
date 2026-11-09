package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.Track1PurchaseLedgerBatch;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface Track1PurchaseLedgerBatchRepository extends JpaRepository<Track1PurchaseLedgerBatch, UUID> {
    Optional<Track1PurchaseLedgerBatch> findByCreatedByMembershipIdAndEventIdAndIdempotencyKeyDigest(UUID membership, UUID event, String digest);

    Optional<Track1PurchaseLedgerBatch> findByTenantIdAndEventIdAndPerformanceIdAndAcceptedSourceVersion(UUID tenant, UUID event, UUID performance, String version);

    Optional<Track1PurchaseLedgerBatch> findByIdAndTenantIdAndEventId(UUID id, UUID tenant, UUID event);

    Page<Track1PurchaseLedgerBatch> findByTenantIdAndEventId(UUID tenant, UUID event, Pageable pageable);

    @Query("select coalesce(max(b.ledgerVersion), 0) from Track1PurchaseLedgerBatch b "
            + "where b.tenantId = :tenant and b.eventId = :event and b.performanceId = :performance")
    int maxLedgerVersion(@Param("tenant") UUID tenant, @Param("event") UUID event, @Param("performance") UUID performance);
}
