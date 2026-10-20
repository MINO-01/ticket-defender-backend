package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.persistence.TenantEventRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantRepository;
import com.ticket.defender_core.domain.AccessRecordStatus;
import com.ticket.defender_core.domain.TenantEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PublicEventLookup {
    private final TenantEventRepository events;
    private final TenantRepository tenants;

    @Transactional(readOnly = true)
    public TenantEvent requireOpenEvent(UUID eventId) {
        return events.findById(eventId)
                .filter(event -> event.isPublicIntakeEnabled()
                        && AccessRecordStatus.ACTIVE.name().equals(event.getStatus()))
                .filter(event -> tenants.findById(event.getTenantId())
                        .filter(tenant -> AccessRecordStatus.ACTIVE.name().equals(tenant.getStatus())).isPresent())
                .orElseThrow(() -> new FanReportApiException(HttpStatus.NOT_FOUND, "EVENT_NOT_AVAILABLE"));
    }
}
