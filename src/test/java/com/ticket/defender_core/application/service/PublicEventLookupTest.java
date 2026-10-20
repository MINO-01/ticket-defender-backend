package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.persistence.TenantEventRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantRepository;
import com.ticket.defender_core.domain.AccessRecordStatus;
import com.ticket.defender_core.domain.Tenant;
import com.ticket.defender_core.domain.TenantEvent;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PublicEventLookupTest {
    private final TenantEventRepository events = mock(TenantEventRepository.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final PublicEventLookup lookup = new PublicEventLookup(events, tenants);
    private final UUID tenantId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();

    @Test
    void onlyActiveOptedInEventUnderActiveTenantIsAccepted() {
        TenantEvent event = new TenantEvent(eventId, tenantId, "same-name", "Event", AccessRecordStatus.ACTIVE);
        when(events.findById(eventId)).thenReturn(Optional.of(event));
        when(tenants.findById(tenantId)).thenReturn(Optional.of(new Tenant(tenantId, "Tenant", AccessRecordStatus.ACTIVE)));
        assertUnavailable();
        verifyNoInteractions(tenants);
        event.setPublicIntakeEnabled(true);
        assertThat(lookup.requireOpenEvent(eventId).getTenantId()).isEqualTo(tenantId);
    }

    @Test
    void missingInactiveEventOrInactiveTenantIsHidden() {
        assertUnavailable();
        TenantEvent inactive = new TenantEvent(eventId, tenantId, "ref", "Event", AccessRecordStatus.INACTIVE);
        inactive.setPublicIntakeEnabled(true);
        when(events.findById(eventId)).thenReturn(Optional.of(inactive));
        assertUnavailable();
        TenantEvent active = new TenantEvent(eventId, tenantId, "ref", "Event", AccessRecordStatus.ACTIVE);
        active.setPublicIntakeEnabled(true);
        when(events.findById(eventId)).thenReturn(Optional.of(active));
        when(tenants.findById(tenantId)).thenReturn(Optional.of(new Tenant(tenantId, "Tenant", AccessRecordStatus.INACTIVE)));
        assertUnavailable();
    }

    private void assertUnavailable() {
        assertThatThrownBy(() -> lookup.requireOpenEvent(eventId))
                .isInstanceOf(FanReportApiException.class).hasMessage("EVENT_NOT_AVAILABLE");
    }
}
