package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.TicketAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;

public interface TicketAuditRepository extends JpaRepository<TicketAudit, Long> {
    List<TicketAudit> findAllByPaymentHash(String paymentHash);

    List<TicketAudit> findByPaymentHashIn(List<String> paymentHashes);
}
