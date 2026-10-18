package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.AccessRecordStatus;
import com.ticket.defender_core.domain.TenantEvent;
import com.ticket.defender_core.security.EventReferenceLookup;
import com.ticket.defender_core.security.EventScopeLookup;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Repository
public class JpaEventScopeLookup implements EventScopeLookup, EventReferenceLookup {
    private final TenantRepository tenants;
    private final TenantEventRepository events;

    public JpaEventScopeLookup(TenantRepository tenants, TenantEventRepository events) {
        this.tenants = tenants;
        this.events = events;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> findTenantId(UUID eventId) {
        return events.findById(eventId)
                .filter(event -> AccessRecordStatus.ACTIVE.name().equals(event.getStatus()))
                .map(TenantEvent::getTenantId)
                .filter(this::activeTenant);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> findActiveEventId(UUID tenantId, String externalEventRef) {
        if (!activeTenant(tenantId)) {
            return Optional.empty();
        }
        return events.findByTenantIdAndExternalEventRef(tenantId, externalEventRef)
                .filter(event -> externalEventRef.equals(event.getExternalEventRef())
                        && AccessRecordStatus.ACTIVE.name().equals(event.getStatus()))
                .map(TenantEvent::getId);
    }

    private boolean activeTenant(UUID tenantId) {
        return tenants.findById(tenantId)
                .filter(tenant -> AccessRecordStatus.ACTIVE.name().equals(tenant.getStatus()))
                .isPresent();
    }
}
