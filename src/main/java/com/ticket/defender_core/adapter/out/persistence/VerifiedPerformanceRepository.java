package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.VerifiedPerformance;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VerifiedPerformanceRepository extends JpaRepository<VerifiedPerformance, UUID> {
    Optional<VerifiedPerformance> findByTenantIdAndEventIdAndPerformanceRef(UUID tenantId, UUID eventId,
            String performanceRef);

    List<VerifiedPerformance> findByTenantIdAndEventIdOrderByPerformanceRefAsc(UUID tenantId, UUID eventId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from VerifiedPerformance p where p.id = :id and p.tenantId = :tenantId and p.eventId = :eventId")
    Optional<VerifiedPerformance> lockByIdAndScope(@Param("id") UUID id, @Param("tenantId") UUID tenantId,
            @Param("eventId") UUID eventId);
}
