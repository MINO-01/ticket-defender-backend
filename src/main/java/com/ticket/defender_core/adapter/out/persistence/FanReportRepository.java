package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.FanReport;
import com.ticket.defender_core.domain.EvidenceAsset;
import org.springframework.data.domain.Page;
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
    /**
     * 관리자 목록용 요약 조회입니다. tenant/event 범위는 서버가 확인한 값만 사용하며,
     * idx_fan_reports_scope_received(tenant_id, event_id, received_at)와 기본 키로 같은 시각도 안정적으로 정렬합니다.
     */
    @Query(value = """
            select r.fanReportId as fanReportId, r.reportType as reportType, r.intakeStatus as intakeStatus,
                   r.receivedAt as receivedAt, r.submittedPerformanceAt as submittedPerformanceAt
            from FanReport r
            where r.tenantId = :tenantId and r.eventId = :eventId
              and (:status is null or r.intakeStatus = :status)
            order by r.receivedAt desc, r.fanReportId desc
            """, countQuery = """
            select count(r) from FanReport r
            where r.tenantId = :tenantId and r.eventId = :eventId
              and (:status is null or r.intakeStatus = :status)
            """)
    Page<AdminFanReportRow> findAdminPage(@Param("tenantId") UUID tenantId, @Param("eventId") UUID eventId,
            @Param("status") FanReport.IntakeStatus status, Pageable page);

    /** 관리자 목록에 필요한 제보 열만 담는 조회 결과입니다. */
    interface AdminFanReportRow {
        UUID getFanReportId();
        FanReport.ReportType getReportType();
        FanReport.IntakeStatus getIntakeStatus();
        LocalDateTime getReceivedAt();
        LocalDateTime getSubmittedPerformanceAt();
    }

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
