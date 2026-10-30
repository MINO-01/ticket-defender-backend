package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.IssuanceHold;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface IssuanceHoldRepository extends JpaRepository<IssuanceHold, UUID> {
    boolean existsByTenantIdAndEventIdAndCaseIdAndStatus(
            UUID tenantId, UUID eventId, UUID caseId, IssuanceHold.Status status);

    @Query("select hold.caseId from IssuanceHold hold where hold.id = :id")
    Optional<UUID> findCaseIdById(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select hold from IssuanceHold hold where hold.id = :id")
    Optional<IssuanceHold> findByIdForUpdate(UUID id);
}
