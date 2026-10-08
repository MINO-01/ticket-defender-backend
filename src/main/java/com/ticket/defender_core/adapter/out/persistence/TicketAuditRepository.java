package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.AuditStatus;
import com.ticket.defender_core.domain.EvidenceType;
import com.ticket.defender_core.domain.TicketAudit;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface TicketAuditRepository extends JpaRepository<TicketAudit, Long> {

    List<TicketAudit> findAllByPaymentHash(String paymentHash);

    List<TicketAudit> findByPaymentHashIn(List<String> paymentHashes);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE TicketAudit t SET t.status = :newStatus WHERE t.paymentHash = :paymentHash AND t.status = :oldStatus")
    int updateStatusByPaymentHash(
            @Param("paymentHash") String paymentHash,
            @Param("newStatus") AuditStatus newStatus,
            @Param("oldStatus") AuditStatus oldStatus
    );

    List<TicketAudit> findAllByEvidenceTypeAndStatus(EvidenceType type, AuditStatus status);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE TicketAudit t SET t.status = :newStatus WHERE t.id = :id AND t.status = :oldStatus")
    int updateStatusById(
            @Param("id") Long id,
            @Param("newStatus") AuditStatus newStatus,
            @Param("oldStatus") AuditStatus oldStatus
    );

    /** 접수 시각이 같은 경우 감사 ID가 낮은 제보를 우선하며, 이전 데이터는 detectedAt으로 정렬합니다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT t FROM TicketAudit t
            WHERE t.reservationNo = :reservationNo
              AND t.evidenceType = :evidenceType
            ORDER BY COALESCE(t.reportReceivedAt, t.detectedAt) ASC, t.id ASC
            """)
    List<TicketAudit> findEarliestReportByReservationNoAndEvidenceType(
            @Param("reservationNo") String reservationNo,
            @Param("evidenceType") EvidenceType evidenceType,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<TicketAudit> findByReservationNoAndIdNotAndStatus(
            String reservationNo,
            Long excludeAuditId,
            AuditStatus status
    );

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE TicketAudit t SET t.status = :newStatus WHERE t.reservationNo = :reservationNo AND t.id != :excludeAuditId AND t.status = :oldStatus")
    int rejectDuplicateAudits(
            @Param("reservationNo") String reservationNo,
            @Param("excludeAuditId") Long excludeAuditId,
            @Param("newStatus") AuditStatus newStatus,
            @Param("oldStatus") AuditStatus oldStatus
    );
}
