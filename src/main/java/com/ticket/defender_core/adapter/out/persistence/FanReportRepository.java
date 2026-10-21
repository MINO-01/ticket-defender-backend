package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.FanReport;
import com.ticket.defender_core.domain.EvidenceAsset;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    @Query("""
            select r from FanReport r
            where r.receivedAt < :before and (
                r.intakeStatus = :pending
                or (r.intakeStatus = :failed and exists (
                    select a.evidenceId from EvidenceAsset a
                    where a.fanReportId = r.fanReportId and a.storageStatus <> :assetFailed
                ))
            )
            order by r.receivedAt asc, r.fanReportId asc
            """)
    List<FanReport> findNeedingCleanup(@Param("before") LocalDateTime before,
            @Param("pending") FanReport.IntakeStatus pending,
            @Param("failed") FanReport.IntakeStatus failed,
            @Param("assetFailed") EvidenceAsset.StorageStatus assetFailed, Pageable limit);
}
