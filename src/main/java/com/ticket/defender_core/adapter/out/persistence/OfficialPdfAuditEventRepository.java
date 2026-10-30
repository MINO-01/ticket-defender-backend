package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.OfficialPdfAuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OfficialPdfAuditEventRepository extends JpaRepository<OfficialPdfAuditEvent, UUID> {
    List<OfficialPdfAuditEvent> findByIssuanceIdOrderByOccurredAtAscIdAsc(UUID issuanceId);
}
