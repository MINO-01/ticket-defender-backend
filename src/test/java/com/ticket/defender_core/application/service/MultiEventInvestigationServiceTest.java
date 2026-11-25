package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.api.FastApiAdapter;
import com.ticket.defender_core.adapter.out.api.dto.MultiEventAnalysisDto;
import com.ticket.defender_core.security.AdminRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MultiEventInvestigationServiceTest {
    private static final UUID TENANT = UUID.fromString("00000000-0000-4000-8000-000000000010");
    private static final UUID MEMBERSHIP = UUID.fromString("00000000-0000-4000-8000-000000000020");
    private static final UUID EVENT_A = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID EVENT_B = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-4000-8000-000000000030");
    private static final Set<AdminRole> ROLES = MultiEventInvestigationService.ROLES;

    @Mock JdbcTemplate jdbc;
    @Mock TransactionTemplate tx;
    @Mock CandidatePipelineAccess access;
    @Mock FastApiAdapter data;

    private MultiEventInvestigationService service;
    private final AtomicReference<String> runListSql = new AtomicReference<>();
    private final AtomicReference<String> baselineSql = new AtomicReference<>();
    private final AtomicReference<Object[]> baselineArguments = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        service = new MultiEventInvestigationService(jdbc, tx, access, data, true, "supplier-a");
    }

    @Test
    void listFiltersAllCurrentEventGrantsBeforeApplyingPagination() {
        when(access.event(EVENT_A, ROLES)).thenReturn(scope(EVENT_A));
        when(access.event(EVENT_B, ROLES)).thenThrow(new CaseApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND"));
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("from td_multi_event_investigation_runs r")) {
                runListSql.set(sql);
                return List.of(run());
            }
            if (sql.contains("select distinct event_id")) return List.of(EVENT_A, EVENT_B);
            return List.of();
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));

        assertThatThrownBy(() -> service.list(EVENT_A, 0, 20))
                .isInstanceOf(CaseApiException.class)
                .extracting("code").isEqualTo("RESOURCE_NOT_FOUND");

        String sql = runListSql.get();
        assertThat(sql).contains("current_grant.membership_id=?");
        assertThat(sql.indexOf("current_grant.membership_id=?")).isLessThan(sql.indexOf("limit ? offset ?"));
        verify(access).event(EVENT_B, ROLES);
    }

    @Test
    void candidateAndRetryReadsAreDeniedAfterRelatedEventGrantIsRevoked() {
        when(access.event(EVENT_A, ROLES)).thenReturn(scope(EVENT_A));
        when(access.event(EVENT_B, ROLES)).thenThrow(new CaseApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND"));
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("from td_multi_event_investigation_runs r")) return List.of(run());
            if (sql.contains("select distinct event_id")) return List.of(EVENT_A, EVENT_B);
            return List.of();
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));

        assertDenied(() -> service.get(EVENT_A, RUN_ID));
        assertDenied(() -> service.candidates(EVENT_A, RUN_ID, 0, 20));
        assertDenied(() -> service.members(EVENT_A, RUN_ID, "multi-" + "a".repeat(64), 0, 20));
        assertDenied(() -> service.relationships(EVENT_A, RUN_ID, "multi-" + "a".repeat(64), 0, 20));
        assertDenied(() -> service.scopes(EVENT_A, RUN_ID));
        assertDenied(() -> service.retry(EVENT_A, RUN_ID));

        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verify(data, never()).analyzeMultiEvent(any());
    }

    @Test
    void runReadsAreHiddenWhenAnotherScopeResolvesToADifferentTenant() {
        when(access.event(EVENT_A, ROLES)).thenReturn(scope(EVENT_A));
        when(access.event(EVENT_B, ROLES)).thenReturn(new CandidatePipelineAccess.Scope(
                UUID.fromString("00000000-0000-4000-8000-000000000099"), EVENT_B, MEMBERSHIP, null));
        stubRunAndScopeQueries();

        assertDenied(() -> service.get(EVENT_A, RUN_ID));
        assertDenied(() -> service.retry(EVENT_A, RUN_ID));

        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void runReadsAreHiddenWhenAnotherScopeResolvesToADifferentMembership() {
        when(access.event(EVENT_A, ROLES)).thenReturn(scope(EVENT_A));
        when(access.event(EVENT_B, ROLES)).thenReturn(new CandidatePipelineAccess.Scope(
                TENANT, EVENT_B, UUID.fromString("00000000-0000-4000-8000-000000000099"), null));
        stubRunAndScopeQueries();

        assertDenied(() -> service.get(EVENT_A, RUN_ID));
        assertDenied(() -> service.retry(EVENT_A, RUN_ID));

        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void disabledFeatureRejectsCreateRetryAndWorkerWithoutCreatingPendingWork() {
        service = new MultiEventInvestigationService(jdbc, tx, access, data, false, "supplier-a");

        assertThatThrownBy(() -> service.create("idempotency", null))
                .isInstanceOf(CaseApiException.class)
                .extracting("code").isEqualTo("MULTI_EVENT_DISABLED");
        assertThatThrownBy(() -> service.retry(EVENT_A, RUN_ID))
                .isInstanceOf(CaseApiException.class)
                .extracting("code").isEqualTo("MULTI_EVENT_DISABLED");
        service.consumeOne();

        verifyNoInteractions(jdbc, tx, access, data);
    }

    @Test
    void baselineQueryMatchesTheExactCandidateAccountsAndCompatibleScope() {
        String accountA = "a".repeat(64);
        String accountB = "b".repeat(64);
        var scopeA = new MultiEventAnalysisDto.LedgerScope("event-a",
                UUID.fromString("00000000-0000-4000-8000-000000000041"),
                UUID.fromString("00000000-0000-4000-8000-000000000051"), "batch-a");
        var scopeB = new MultiEventAnalysisDto.LedgerScope("event-b",
                UUID.fromString("00000000-0000-4000-8000-000000000042"),
                UUID.fromString("00000000-0000-4000-8000-000000000052"), "batch-b");
        doAnswer(invocation -> {
            baselineSql.set(invocation.getArgument(0));
            Object[] invocationArguments = invocation.getArguments();
            Object[] parameters = invocationArguments.length == 3 && invocationArguments[2] instanceof Object[] array
                    ? array : java.util.Arrays.copyOfRange(invocationArguments, 2, invocationArguments.length);
            baselineArguments.set(parameters);
            return 0.31d;
        }).when(jdbc).query(anyString(), any(ResultSetExtractor.class), any(Object[].class));

        Double result = service.baselineMaxScore(RUN_ID, List.of(accountA, accountB), List.of(scopeA, scopeB));

        assertThat(result).isEqualTo(0.31d);
        assertThat(baselineSql.get()).contains("json_table", "c.member_count=?", "count(distinct m.account_reference_hmac)=?",
                "j.tenant_id=", "j.hmac_key_version=");
        assertThat(baselineSql.get()).doesNotContain("select max(c.risk_score)");
        assertThat(baselineArguments.get()[0]).isEqualTo("[\"" + accountA + "\",\"" + accountB + "\"]");
    }

    private void assertDenied(Runnable action) {
        assertThatThrownBy(() -> action.run())
                .isInstanceOf(CaseApiException.class)
                .extracting("code").isEqualTo("RESOURCE_NOT_FOUND");
    }

    private void stubRunAndScopeQueries() {
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("from td_multi_event_investigation_runs r")) return List.of(run());
            if (sql.contains("select distinct event_id")) return List.of(EVENT_A, EVENT_B);
            return List.of();
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    private static CandidatePipelineAccess.Scope scope(UUID event) {
        return new CandidatePipelineAccess.Scope(TENANT, event, MEMBERSHIP, null);
    }

    private static MultiEventInvestigationService.Run run() {
        OffsetDateTime now = OffsetDateTime.parse("2026-11-25T00:00:00Z");
        return new MultiEventInvestigationService.Run(RUN_ID, "COMPLETED", null, "d".repeat(64),
                "score-v2-exp2-multi-event-recurrence", "projection-v2.2-exp2-ipwindow-softhub", "gds-2.6.6",
                now, now.minusDays(2), now.minusDays(1), now.minusDays(1), now, now);
    }
}
