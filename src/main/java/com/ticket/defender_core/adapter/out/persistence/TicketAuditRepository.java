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

    /** 같은 결제 토큰의 감사 내역을 조회합니다. */
    List<TicketAudit> findAllByPaymentHash(String paymentHash);

    /** 결제 토큰 목록에 해당하는 내역을 조회합니다. */
    List<TicketAudit> findByPaymentHashIn(List<String> paymentHashes);

    /** 결제 토큰과 현재 상태가 일치하는 내역을 한꺼번에 변경합니다. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE TicketAudit t SET t.status = :newStatus WHERE t.paymentHash = :paymentHash AND t.status = :oldStatus")
    int updateStatusByPaymentHash(
            @Param("paymentHash") String paymentHash,
            @Param("newStatus") AuditStatus newStatus,
            @Param("oldStatus") AuditStatus oldStatus
    );

    /** 증거 유형과 상태로 내역을 조회합니다. */
    List<TicketAudit> findAllByEvidenceTypeAndStatus(EvidenceType type, AuditStatus status);

    /** 현재 상태가 일치할 때 감사 상태를 변경합니다. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE TicketAudit t SET t.status = :newStatus WHERE t.id = :id AND t.status = :oldStatus")
    int updateStatusById(
            @Param("id") Long id,
            @Param("newStatus") AuditStatus newStatus,
            @Param("oldStatus") AuditStatus oldStatus
    );

    /** 접수 순서가 늦은 제보를 잠금 조회합니다. */
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

    /** 같은 예매 건에서 유형과 상태가 일치하는 제보를 잠금 조회합니다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<TicketAudit> findByReservationNoAndIdNotAndEvidenceTypeAndStatus(
            String reservationNo,
            Long excludeAuditId,
            EvidenceType evidenceType,
            AuditStatus status
    );

    /** 유형과 현재 상태가 일치하는 후순위 제보를 반려합니다. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE TicketAudit t SET t.status = :newStatus WHERE t.reservationNo = :reservationNo AND t.id != :excludeAuditId AND t.evidenceType = :evidenceType AND t.status = :oldStatus")
    int rejectDuplicateAudits(
            @Param("reservationNo") String reservationNo,
            @Param("excludeAuditId") Long excludeAuditId,
            @Param("evidenceType") EvidenceType evidenceType,
            @Param("newStatus") AuditStatus newStatus,
            @Param("oldStatus") AuditStatus oldStatus
    );
}
