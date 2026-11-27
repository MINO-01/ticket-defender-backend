package com.ticket.defender_core.application.service;

import com.ticket.defender_core.security.AdminAccessService;
import com.ticket.defender_core.security.AdminRole;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InvestigationTargetServiceTest {
    private static final UUID EVENT = UUID.fromString("af8cc0af-9c65-46d1-bd07-0fcf8433a3a0");

    @Test
    void unauthorizedRoleIsHiddenAsNotFoundBeforeAnyTargetQuery() {
        CandidatePipelineAccess access = mock(CandidatePipelineAccess.class);
        AdminAccessService adminAccess = mock(AdminAccessService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        InvestigationTargetService service = new InvestigationTargetService(access, adminAccess, jdbc, cipher());
        when(access.event(eq(EVENT), eq(Set.of(AdminRole.TENANT_ADMIN, AdminRole.ANALYST))))
                .thenThrow(new AccessDeniedException("role denied"));

        assertThatThrownBy(() -> service.bulkClusterTargets(EVENT, UUID.randomUUID(), UUID.randomUUID(),
                "cluster-1", 0, 50, "synthetic review"))
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));

        verify(jdbc, never()).queryForObject(org.mockito.ArgumentMatchers.anyString(),
                eq(Integer.class), org.mockito.ArgumentMatchers.<Object[]>any());
        verify(jdbc, never()).query(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.RowMapper.class),
                org.mockito.ArgumentMatchers.<Object[]>any());
    }

    @Test
    void multiEventTargetRequiresAccessToEveryEventInTheRun() {
        UUID tenant = UUID.fromString("7a7626f7-b313-43ee-97ae-822069ad78f0");
        UUID membership = UUID.fromString("7a7626f7-b313-43ee-97ae-822069ad78f1");
        UUID secondEvent = UUID.fromString("7a7626f7-b313-43ee-97ae-822069ad78f2");
        UUID runId = UUID.randomUUID();
        String candidateId = "multi-" + "a".repeat(64);
        Set<AdminRole> roles = Set.of(AdminRole.TENANT_ADMIN, AdminRole.ANALYST);
        CandidatePipelineAccess access = mock(CandidatePipelineAccess.class);
        AdminAccessService adminAccess = mock(AdminAccessService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        InvestigationTargetService service = new InvestigationTargetService(access, adminAccess, jdbc, cipher());
        when(access.event(eq(EVENT), eq(roles)))
                .thenReturn(new CandidatePipelineAccess.Scope(tenant, EVENT, membership, null));
        when(access.event(eq(secondEvent), eq(roles))).thenThrow(new AccessDeniedException("event denied"));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);
        when(jdbc.query(contains("select distinct event_id"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(EVENT, secondEvent));

        assertThatThrownBy(() -> service.multiEventTargets(EVENT, runId, candidateId, 0, 50, "synthetic review"))
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));

        verify(access).event(eq(secondEvent), eq(roles));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void missingRevealReasonIsRejectedBeforeAuthorizationOrDatabaseAccess() {
        CandidatePipelineAccess access = mock(CandidatePipelineAccess.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        InvestigationTargetService service = new InvestigationTargetService(access, mock(AdminAccessService.class), jdbc, cipher());

        assertThatThrownBy(() -> service.revealBulkCluster(EVENT, UUID.randomUUID(), UUID.randomUUID(), "cluster-1",
                0, 50, "  "))
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        verifyNoInteractions(access, jdbc);
    }

    @Test
    void maskedTargetsAndRevealedDtoStringDoNotExposeOriginalReferences() {
        var revealed = new InvestigationTargetService.RevealedTarget("target-1", "synthetic-account-42",
                "synthetic-reservation-87", "synthetic-ticket-19", UUID.randomUUID(),
                LocalDateTime.parse("2026-01-01T12:00:00"), "ACTIVE", null, null, null, List.of());

        var masked = InvestigationTargetService.masked(revealed);

        assertThat(masked.accountReference()).isEqualTo("****42");
        assertThat(masked.reservationReference()).isEqualTo("****87");
        assertThat(masked.ticketReference()).isEqualTo("****19");
        assertThat(masked.toString()).doesNotContain("synthetic-account-42", "synthetic-reservation-87", "synthetic-ticket-19");
        assertThat(revealed.toString()).doesNotContain("synthetic-account-42", "synthetic-reservation-87", "synthetic-ticket-19");
    }

    @Test
    void csvExportNeutralizesSpreadsheetFormulasInReferences() {
        var row = new InvestigationTargetService.RevealedTarget("target-1", " =HYPERLINK(\"https://example.invalid\")",
                "-synthetic-reservation", "@synthetic-ticket", UUID.randomUUID(),
                LocalDateTime.parse("2026-01-01T12:00:00"), "ACTIVE", null, null, null, List.of());

        String csv = new String(InvestigationTargetService.csv(List.of(row)), java.nio.charset.StandardCharsets.UTF_8);

        assertThat(csv).contains("\"' =HYPERLINK(\"\"https://example.invalid\"\")\"");
        assertThat(csv).contains("\"'-synthetic-reservation\"");
        assertThat(csv).contains("\"'@synthetic-ticket\"");
        assertThat(csv.lines().skip(1).findFirst().orElseThrow()).doesNotEndWith(",");
    }

    private static InvestigationReferenceCipher cipher() {
        var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        String master = java.util.Base64.getEncoder().encodeToString(new byte[32]);
        return new InvestigationReferenceCipher(beans.getBeanProvider(InvestigationRevealKeySource.class), true,
                "SYNTHETIC_CONFIG", master, "synthetic-test-v1");
    }
}
