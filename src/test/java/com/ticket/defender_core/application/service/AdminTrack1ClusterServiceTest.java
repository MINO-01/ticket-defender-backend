package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.persistence.MacroAuditEvidenceReadRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantEventRepository;
import com.ticket.defender_core.domain.AccessRecordStatus;
import com.ticket.defender_core.domain.TenantEvent;
import com.ticket.defender_core.security.AdminAccessService;
import com.ticket.defender_core.security.AdminActor;
import com.ticket.defender_core.security.AdminRole;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminTrack1ClusterServiceTest {
    @Test
    void databaseFailureDoesNotBecomeAnEmptyList() {
        UUID tenantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        AdminAccessService access = mock(AdminAccessService.class);
        TenantEventRepository events = mock(TenantEventRepository.class);
        MacroAuditEvidenceReadRepository evidence = mock(MacroAuditEvidenceReadRepository.class);
        AdminTrack1ClusterService service = new AdminTrack1ClusterService(access, events, evidence);

        when(access.requireEventAccess(eq(eventId), eq(Set.of(AdminRole.TENANT_ADMIN, AdminRole.ANALYST,
                AdminRole.REVIEWER, AdminRole.APPROVER)))).thenReturn(new AdminActor("admin", tenantId, eventId));
        when(events.findById(eventId)).thenReturn(Optional.of(new TenantEvent(
                eventId, tenantId, "external-event", "공연", AccessRecordStatus.ACTIVE)));
        when(evidence.countClusters(tenantId, eventId))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThatThrownBy(() -> service.list(eventId, 0, 20, "priority-desc"))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }
}
