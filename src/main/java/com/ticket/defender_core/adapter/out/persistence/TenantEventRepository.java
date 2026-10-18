package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.TenantEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TenantEventRepository extends JpaRepository<TenantEvent, UUID> {
    Optional<TenantEvent> findByTenantIdAndExternalEventRef(UUID tenantId, String externalEventRef);
}
