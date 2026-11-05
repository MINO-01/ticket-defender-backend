package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.ReservationDataSource;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ReservationDataSourceRepository extends JpaRepository<ReservationDataSource, UUID> {
    Optional<ReservationDataSource> findByTenantIdAndEventIdAndPerformanceId(UUID tenantId, UUID eventId,
            UUID performanceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ReservationDataSource s where s.tenantId = :tenantId and s.eventId = :eventId "
            + "and s.performanceId = :performanceId")
    Optional<ReservationDataSource> lockByScope(@Param("tenantId") UUID tenantId, @Param("eventId") UUID eventId,
            @Param("performanceId") UUID performanceId);
}
