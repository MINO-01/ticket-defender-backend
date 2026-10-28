package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.IssuanceHold;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface IssuanceHoldRepository extends JpaRepository<IssuanceHold, UUID> {
    boolean existsByTenantIdAndEventIdAndCaseIdAndStatus(
            UUID tenantId, UUID eventId, UUID caseId, IssuanceHold.Status status);
}
