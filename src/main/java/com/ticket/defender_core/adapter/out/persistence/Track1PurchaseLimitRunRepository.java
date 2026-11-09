package com.ticket.defender_core.adapter.out.persistence;

import org.springframework.data.jpa.repository.Query;
import com.ticket.defender_core.domain.Track1PurchaseLimitRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface Track1PurchaseLimitRunRepository extends JpaRepository<Track1PurchaseLimitRun, UUID> {
    Optional<Track1PurchaseLimitRun> findByCreatedByMembershipIdAndEventIdAndIdempotencyKeyDigest(UUID membership, UUID event, String digest);

    Optional<Track1PurchaseLimitRun> findByActiveLedgerBatchId(UUID batchId);

    Optional<Track1PurchaseLimitRun> findByIdAndTenantIdAndEventId(UUID id, UUID tenant, UUID event);

    Page<Track1PurchaseLimitRun> findByTenantIdAndEventId(UUID tenant, UUID event, Pageable pageable);

    @Query("select r.executionStatus, count(r) from Track1PurchaseLimitRun r where r.tenantId = :tenant and r.eventId = :event group by r.executionStatus")
    java.util.List<Object[]> countByExecutionStatus(@org.springframework.data.repository.query.Param("tenant") UUID tenant, @org.springframework.data.repository.query.Param("event") UUID event);
}
