package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.FanReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FanReportRepository extends JpaRepository<FanReport, UUID> {
    Optional<FanReport> findByReceiptNumber(String receiptNumber);
    Optional<FanReport> findByIdempotencyKeyDigest(String digest);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from FanReport r where r.fanReportId = :id")
    Optional<FanReport> lockById(UUID id);

    List<FanReport> findTop50ByIntakeStatusInAndReceivedAtBeforeOrderByReceivedAtAsc(
            List<FanReport.IntakeStatus> statuses, LocalDateTime before);
}
