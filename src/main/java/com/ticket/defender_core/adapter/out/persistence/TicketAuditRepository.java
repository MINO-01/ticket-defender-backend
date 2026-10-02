package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.AuditStatus;
import com.ticket.defender_core.domain.EvidenceType;
import com.ticket.defender_core.domain.TicketAudit;
import org.springframework.data.jpa.repository.JpaRepository;
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
    @Query("UPDATE TicketAudit t SET t.status = :newStatus WHERE t.reservationNo IN :reservationNos AND t.status = :oldStatus")
    int bulkUpdateStatusToReportIssued(
            @Param("reservationNos") List<String> reservationNos,
            @Param("newStatus") com.ticket.defender_core.domain.AuditStatus newStatus,
            @Param("oldStatus") com.ticket.defender_core.domain.AuditStatus oldStatus
    );
}