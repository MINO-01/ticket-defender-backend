package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.TenantEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.UUID;

public interface TenantEventRepository extends JpaRepository<TenantEvent, UUID> {
    Optional<TenantEvent> findByTenantIdAndExternalEventRef(UUID tenantId, String externalEventRef);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select e from TenantEvent e where e.id = :id")
    Optional<TenantEvent> lockById(UUID id);
}
