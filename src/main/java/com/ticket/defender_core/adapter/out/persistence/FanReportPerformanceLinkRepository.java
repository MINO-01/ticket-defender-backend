package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.FanReportPerformanceLink;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FanReportPerformanceLinkRepository extends JpaRepository<FanReportPerformanceLink, UUID> {
    Optional<FanReportPerformanceLink> findByActiveFanReportId(UUID fanReportId);

    List<FanReportPerformanceLink> findByFanReportIdOrderByCreatedAtDesc(UUID fanReportId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from FanReportPerformanceLink l where l.activeFanReportId = :fanReportId")
    Optional<FanReportPerformanceLink> lockActiveByFanReportId(@Param("fanReportId") UUID fanReportId);
}
