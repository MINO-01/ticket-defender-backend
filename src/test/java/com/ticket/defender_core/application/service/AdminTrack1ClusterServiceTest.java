package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.persistence.MacroAuditEvidenceReadRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantEventRepository;
import com.ticket.defender_core.adapter.out.persistence.VerifiedPerformanceRepository;
import com.ticket.defender_core.domain.AccessRecordStatus;
import com.ticket.defender_core.domain.MacroAuditEvidence;
import com.ticket.defender_core.domain.TenantEvent;
import com.ticket.defender_core.domain.VerifiedPerformance;
import com.ticket.defender_core.security.AdminAccessService;
import com.ticket.defender_core.security.AdminActor;
import com.ticket.defender_core.security.AdminRole;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
        VerifiedPerformanceRepository performances = mock(VerifiedPerformanceRepository.class);
        Track1LedgerSeatLookupService ledgerSeats = mock(Track1LedgerSeatLookupService.class);
        AdminTrack1ClusterService service = new AdminTrack1ClusterService(access, events, evidence, performances,
                ledgerSeats);

        when(access.requireEventAccess(eq(eventId), eq(Set.of(AdminRole.TENANT_ADMIN, AdminRole.ANALYST,
                AdminRole.REVIEWER, AdminRole.APPROVER)))).thenReturn(new AdminActor("admin", tenantId, eventId));
        when(events.findById(eventId)).thenReturn(Optional.of(new TenantEvent(
                eventId, tenantId, "external-event", "공연", AccessRecordStatus.ACTIVE)));
        when(evidence.countClusters(tenantId, eventId))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThatThrownBy(() -> service.list(eventId, 0, 20, "priority-desc"))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void detailAddsOnlyServerVerifiedLedgerSeatsWhenPerformanceAndSourceVersionAreBothPresent() {
        UUID tenantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID performanceId = UUID.randomUUID();
        AdminAccessService access = mock(AdminAccessService.class);
        TenantEventRepository events = mock(TenantEventRepository.class);
        MacroAuditEvidenceReadRepository evidence = mock(MacroAuditEvidenceReadRepository.class);
        VerifiedPerformanceRepository performances = mock(VerifiedPerformanceRepository.class);
        Track1LedgerSeatLookupService ledgerSeats = mock(Track1LedgerSeatLookupService.class);
        AdminTrack1ClusterService service = new AdminTrack1ClusterService(access, events, evidence, performances,
                ledgerSeats);
        AdminActor actor = new AdminActor("admin", tenantId, eventId);
        when(access.requireEventAccess(eq(eventId), eq(Set.of(AdminRole.TENANT_ADMIN, AdminRole.ANALYST,
                AdminRole.REVIEWER, AdminRole.APPROVER)))).thenReturn(actor);
        when(events.findById(eventId)).thenReturn(Optional.of(new TenantEvent(eventId, tenantId, "external-event",
                "공연", AccessRecordStatus.ACTIVE)));
        MacroAuditEvidence member = mock(MacroAuditEvidence.class);
        when(member.getAuditId()).thenReturn(17L);
        when(member.getRiskScore()).thenReturn(0.5);
        when(member.getAnalysisAlgorithm()).thenReturn("LOUVAIN");
        when(member.getAnalysisAlgorithmVersion()).thenReturn("v1");
        when(member.getAnalysisCompletedAt()).thenReturn(LocalDateTime.now());
        when(evidence.findMembers(tenantId, eventId, "run", "cluster")).thenReturn(List.of(member));
        VerifiedPerformance performance = mock(VerifiedPerformance.class);
        when(performance.getId()).thenReturn(performanceId);
        when(performance.getTenantId()).thenReturn(tenantId);
        when(performance.getEventId()).thenReturn(eventId);
        when(performance.getStatus()).thenReturn(VerifiedPerformance.Status.ACTIVE);
        when(performances.findById(performanceId)).thenReturn(Optional.of(performance));
        when(ledgerSeats.lookup(eq(tenantId), eq(eventId), eq(performanceId), eq("ledger-v1"), eq(null), eq(null),
                any())).thenReturn(new Track1LedgerSeatLookupService.Result(true, false, List.of(
                new Track1LedgerSeatLookupService.LedgerSeat(17L, Track1LedgerSeatLookupService.Status.FOUND,
                        "A", "1", "2"))));

        var result = service.detail(eventId, "run", "cluster", performanceId, "ledger-v1");

        assertThat(result.members()).singleElement().satisfies(found -> {
            assertThat(found.auditId()).isEqualTo(17L);
            assertThat(found.ledgerSeat().status()).isEqualTo(com.ticket.defender_core.adapter.in.web.dto.Track1ClusterDto.LedgerSeatStatus.FOUND);
            assertThat(found.ledgerSeat().seat()).isEqualTo("2");
        });
    }

    @Test
    void rejectsPartialLedgerScopeBeforeAnyLedgerLookup() {
        AdminTrack1ClusterService service = new AdminTrack1ClusterService(mock(AdminAccessService.class),
                mock(TenantEventRepository.class), mock(MacroAuditEvidenceReadRepository.class),
                mock(VerifiedPerformanceRepository.class), mock(Track1LedgerSeatLookupService.class));

        assertThatThrownBy(() -> service.detail(UUID.randomUUID(), "run", "cluster", UUID.randomUUID(), null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(400));
    }
}
