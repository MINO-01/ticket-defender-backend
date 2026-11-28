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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InvestigationTargetServiceTest {
    private static final UUID EVENT = UUID.fromString("af8cc0af-9c65-46d1-bd07-0fcf8433a3a0");

    @Test
    void memberReasonJsonReturnsOnlyReasonCodesAndAllowsOlderRowsWithoutReasons() {
        assertThat(InvestigationTargetService.reasonCodes("[{\"code\":\"SHARED_PHONE\",\"peerCount\":2,\"performances\":[\"p1\"]}]"))
                .containsExactly("SHARED_PHONE");
        assertThat(InvestigationTargetService.reasonCodes("[]")).isEmpty();
        assertThat(InvestigationTargetService.reasonCodes(null)).isEmpty();
        assertThatThrownBy(() -> InvestigationTargetService.reasonCodes("[{\"peerCount\":2}]"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void multiEventTargetsIncludeMaskedReferencesSeatAndMemberReasonCodes() throws Exception {
        UUID tenant = UUID.fromString("7a7626f7-b313-43ee-97ae-822069ad78f0");
        UUID membership = UUID.fromString("7a7626f7-b313-43ee-97ae-822069ad78f1");
        UUID performance = UUID.fromString("7a7626f7-b313-43ee-97ae-822069ad78f2");
        UUID runId = UUID.randomUUID();
        UUID importId = UUID.randomUUID();
        String candidateId = "multi-" + "a".repeat(64);
        String ticketHmac = "a".repeat(64);
        String reservationHmac = "b".repeat(64);
        String accountHmac = "c".repeat(64);
        var cipher = cipher();
        var encrypted = cipher.encrypt(importId, tenant, EVENT, performance, 7, ticketHmac, reservationHmac,
                accountHmac, "synthetic-account-42", "synthetic-reservation-87", "synthetic-ticket-19");
        CandidatePipelineAccess access = mock(CandidatePipelineAccess.class);
        AdminAccessService adminAccess = mock(AdminAccessService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        InvestigationTargetService service = new InvestigationTargetService(access, adminAccess, jdbc, cipher);
        Set<AdminRole> roles = Set.of(AdminRole.TENANT_ADMIN, AdminRole.ANALYST);
        when(access.event(eq(EVENT), eq(roles)))
                .thenReturn(new CandidatePipelineAccess.Scope(tenant, EVENT, membership, null));
        when(adminAccess.authenticatedSubject()).thenReturn("synthetic-admin");
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
        when(jdbc.query(contains("select distinct event_id"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(EVENT));
        when(jdbc.query(contains("join td_investigation_reference_ciphertexts c"), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    assertThat(sql).contains("td_multi_event_investigation_member_reasons", "t.seat_zone", "t.seat_row", "t.seat_number");
                    RowMapper<?> mapper = invocation.getArgument(1);
                    var row = mock(java.sql.ResultSet.class);
                    when(row.getString(1)).thenReturn(UUID.randomUUID().toString());
                    when(row.getString(2)).thenReturn(importId.toString());
                    when(row.getString(3)).thenReturn(tenant.toString());
                    when(row.getString(4)).thenReturn(EVENT.toString());
                    when(row.getString(5)).thenReturn(performance.toString());
                    when(row.getLong(6)).thenReturn(7L);
                    when(row.getString(7)).thenReturn(ticketHmac);
                    when(row.getString(8)).thenReturn(reservationHmac);
                    when(row.getString(9)).thenReturn(accountHmac);
                    when(row.getBytes(10)).thenReturn(encrypted.ciphertext());
                    when(row.getBytes(11)).thenReturn(encrypted.nonce());
                    when(row.getString(12)).thenReturn(encrypted.keyVersion());
                    when(row.getTimestamp(13)).thenReturn(java.sql.Timestamp.valueOf("2026-11-25 10:00:00"));
                    when(row.getString(14)).thenReturn("ACTIVE");
                    when(row.getString(15)).thenReturn("A");
                    when(row.getString(16)).thenReturn("12");
                    when(row.getString(17)).thenReturn("34");
                    when(row.getString(18)).thenReturn("[{\"code\":\"SHARED_PHONE\",\"peerCount\":1,\"performances\":[\"" + performance + "\"]}]");
                    return List.of(mapper.mapRow(row, 0));
                });

        var page = service.multiEventTargets(EVENT, runId, candidateId, 0, 50, "synthetic review");

        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.accountReference()).isEqualTo("****42");
            assertThat(item.reservationReference()).isEqualTo("****87");
            assertThat(item.ticketReference()).isEqualTo("****19");
            assertThat(item.purchasedAt()).isEqualTo(LocalDateTime.parse("2026-11-25T10:00:00"));
            assertThat(item.seatZone()).isEqualTo("A");
            assertThat(item.seatRow()).isEqualTo("12");
            assertThat(item.seatNumber()).isEqualTo("34");
            assertThat(item.reasons()).containsExactly("SHARED_PHONE");
        });
    }

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

    @Test
    void auditRedactsReferencesSuppliedInRevealReason() throws Exception {
        UUID tenant = UUID.fromString("7a7626f7-b313-43ee-97ae-822069ad78f0");
        UUID membership = UUID.fromString("7a7626f7-b313-43ee-97ae-822069ad78f1");
        UUID performance = UUID.fromString("7a7626f7-b313-43ee-97ae-822069ad78f2");
        UUID jobId = UUID.fromString("7a7626f7-b313-43ee-97ae-822069ad78f3");
        UUID importId = UUID.fromString("7a7626f7-b313-43ee-97ae-822069ad78f4");
        String account = "synthetic-account-42";
        String reservation = "synthetic-reservation-87";
        String ticket = "synthetic-ticket-19";
        String ticketHmac = "a".repeat(64);
        String reservationHmac = "b".repeat(64);
        String accountHmac = "c".repeat(64);
        var cipher = cipher();
        var encrypted = cipher.encrypt(importId, tenant, EVENT, performance, 7, ticketHmac, reservationHmac,
                accountHmac, account, reservation, ticket);

        CandidatePipelineAccess access = mock(CandidatePipelineAccess.class);
        AdminAccessService adminAccess = mock(AdminAccessService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        InvestigationTargetService service = new InvestigationTargetService(access, adminAccess, jdbc, cipher);
        Set<AdminRole> roles = Set.of(AdminRole.TENANT_ADMIN, AdminRole.ANALYST);
        when(access.event(eq(EVENT), eq(roles)))
                .thenReturn(new CandidatePipelineAccess.Scope(tenant, EVENT, membership, null));
        when(adminAccess.authenticatedSubject()).thenReturn("synthetic-admin");
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);

        when(jdbc.query(contains("from td_bulk_ledger_track1_bulk_jobs j"), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    var row = mock(java.sql.ResultSet.class);
                    when(row.getString(1)).thenReturn(UUID.randomUUID().toString());
                    when(row.getString(2)).thenReturn(importId.toString());
                    when(row.getString(3)).thenReturn(tenant.toString());
                    when(row.getString(4)).thenReturn(EVENT.toString());
                    when(row.getString(5)).thenReturn(performance.toString());
                    when(row.getLong(6)).thenReturn(7L);
                    when(row.getString(7)).thenReturn(ticketHmac);
                    when(row.getString(8)).thenReturn(reservationHmac);
                    when(row.getString(9)).thenReturn(accountHmac);
                    when(row.getBytes(10)).thenReturn(encrypted.ciphertext());
                    when(row.getBytes(11)).thenReturn(encrypted.nonce());
                    when(row.getString(12)).thenReturn(encrypted.keyVersion());
                    when(row.getTimestamp(13)).thenReturn(java.sql.Timestamp.valueOf("2026-11-25 10:00:00"));
                    when(row.getString(14)).thenReturn("ACTIVE");
                    return List.of(mapper.mapRow(row, 0));
                });

        AtomicReference<Object[]> auditArguments = new AtomicReference<>();
        doAnswer(invocation -> {
            auditArguments.set(invocation.getArguments());
            return 1;
        }).when(jdbc).update(contains("insert into td_investigation_reference_access_audit"), any(Object[].class));

        var page = service.revealBulkCluster(EVENT, performance, jobId, "cluster-1", 0, 50,
                "Review " + account + " / " + reservation + " / " + ticket);

        assertThat(page.items()).singleElement().satisfies(value -> {
            assertThat(value.accountReference()).isEqualTo(account);
            assertThat(value.reservationReference()).isEqualTo(reservation);
            assertThat(value.ticketReference()).isEqualTo(ticket);
        });
        assertThat(auditArguments.get()).isNotNull();
        String auditedReason = (String) auditArguments.get()[9];
        assertThat(auditedReason).contains("[redacted]").doesNotContain(account, reservation, ticket);
    }

    private static InvestigationReferenceCipher cipher() {
        var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        String master = java.util.Base64.getEncoder().encodeToString(new byte[32]);
        return new InvestigationReferenceCipher(beans.getBeanProvider(InvestigationRevealKeySource.class), true,
                "SYNTHETIC_CONFIG", master, "synthetic-test-v1");
    }
}
