package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.api.FastApiAdapter;
import com.ticket.defender_core.adapter.out.api.FastApiResponseContractException;
import com.ticket.defender_core.adapter.out.api.dto.MultiEventAnalysisDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 같은 공연의 서로 다른 회차를 반복 근거로 쓰는 다회차 실행과 Data 계약 재검증. */
class MultiEventMultiPerformanceTest {
    private static final UUID TENANT = UUID.fromString("00000000-0000-4000-8000-000000000010");
    private static final UUID MEMBERSHIP = UUID.fromString("00000000-0000-4000-8000-000000000020");
    private static final UUID EVENT = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID PERFORMANCE_A = UUID.fromString("00000000-0000-4000-8000-000000000041");
    private static final UUID PERFORMANCE_B = UUID.fromString("00000000-0000-4000-8000-000000000042");
    private static final UUID VERSION_A = UUID.fromString("00000000-0000-4000-8000-000000000051");
    private static final UUID VERSION_B = UUID.fromString("00000000-0000-4000-8000-000000000052");

    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private CandidatePipelineAccess access;
    private MultiEventInvestigationService service;
    private final List<String> updates = new ArrayList<>();
    private final List<Object[]> updateArguments = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        tx = mock(TransactionTemplate.class);
        access = mock(CandidatePipelineAccess.class);
        service = new MultiEventInvestigationService(jdbc, tx, access, mock(FastApiAdapter.class), true, "supplier-a");
        when(access.event(EVENT, MultiEventInvestigationService.ROLES)).thenReturn(new CandidatePipelineAccess.Scope(TENANT, EVENT, MEMBERSHIP, null));
        when(tx.execute(any())).thenAnswer(invocation -> ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            updates.add(invocation.getArgument(0));
            Object[] all = invocation.getArguments();
            updateArguments.add(java.util.Arrays.copyOfRange(all, 1, all.length));
            return 1;
        });
    }

    @Test
    void oneEventWithTwoPerformancesCreatesARunWithTheMultiPerformanceScoreVersion() {
        stubCreate(List.of(scope(PERFORMANCE_A, VERSION_A, "supplier-a"), scope(PERFORMANCE_B, VERSION_B, "supplier-a")), "key-v1", "key-v1");

        var accepted = service.create("idempotency-1", request());

        assertThat(accepted.status()).isEqualTo("PENDING");
        assertThat(updates.getFirst()).contains("'score-v2-exp2-multi-performance-recurrence'").doesNotContain("multi-event-recurrence");
        assertThat(updates.stream().filter(sql -> sql.contains("td_multi_event_investigation_scopes"))).hasSize(2);
    }

    @Test
    void oneEventWithOnePerformanceIsRejected() {
        stubCreate(List.of(scope(PERFORMANCE_A, VERSION_A, "supplier-a")), "key-v1");

        assertThatThrownBy(() -> service.create("idempotency-2", request()))
                .isInstanceOf(CaseApiException.class).extracting("code").isEqualTo("MULTI_EVENT_PERFORMANCE_SCOPE_REQUIRED");
        assertThat(updates).isEmpty();
    }

    @Test
    void mixedSupplierNamespacesAreNeverCombined() {
        stubCreate(List.of(scope(PERFORMANCE_A, VERSION_A, "supplier-a"), scope(PERFORMANCE_B, VERSION_B, "supplier-b")), "key-v1", "key-v1");

        assertThatThrownBy(() -> service.create("idempotency-3", request()))
                .isInstanceOf(CaseApiException.class).extracting("code").isEqualTo("MULTI_EVENT_SUPPLIER_MIXED");
        assertThat(updates).isEmpty();
    }

    @Test
    void mixedHmacKeyVersionsAreNeverCombined() {
        stubCreate(List.of(scope(PERFORMANCE_A, VERSION_A, "supplier-a"), scope(PERFORMANCE_B, VERSION_B, "supplier-a")), "key-v1", "key-v2");

        assertThatThrownBy(() -> service.create("idempotency-4", request()))
                .isInstanceOf(CaseApiException.class).extracting("code").isEqualTo("MULTI_EVENT_IDENTITY_CONTRACT_UNAVAILABLE");
        assertThat(updates).isEmpty();
    }

    @Test
    void candidateSummaryAcceptsOneEventTwoPerformancesWithPaymentAndIpWindowScoreSignals() {
        var candidate = candidate(1, 2, List.of("ip_window:CORPORATE_EDU", "payment_hash"), List.of("address_hash", "device_id_hash", "phone_hash", "refund_account_hash"),
                recurrenceFor(2));
        assertThatCode(() -> service.validateCandidateSummary(candidate, fixedScopes())).doesNotThrowAnyException();
    }

    @Test
    void candidateSummaryRejectsRecurrenceComputedFromEventCountAndUnknownSignals() {
        // 회차 2개, event 1개에서 event 수로 계산한 R(0)은 사전 등록 정의와 다릅니다.
        var eventBased = candidate(1, 2, List.of("device_id_hash"), List.of(), 0.0);
        assertThatThrownBy(() -> service.validateCandidateSummary(eventBased, fixedScopes())).isInstanceOf(FastApiResponseContractException.class);

        var unknown = candidate(1, 2, List.of("raw_ip"), List.of(), recurrenceFor(2));
        assertThatThrownBy(() -> service.validateCandidateSummary(unknown, fixedScopes())).isInstanceOf(FastApiResponseContractException.class);

        var badUnavailable = candidate(1, 2, List.of("device_id_hash"), List.of("ip_window:UNKNOWN"), recurrenceFor(2));
        assertThatThrownBy(() -> service.validateCandidateSummary(badUnavailable, fixedScopes())).isInstanceOf(FastApiResponseContractException.class);

        var retired = new MultiEventAnalysisDto.CandidateSummary("multi-" + "c".repeat(64), "LOW", MultiEventInvestigationService.RETIRED_SCORE_VERSION,
                "projection-v2.2-exp2-ipwindow-softhub", 2, 1, 2, 1, List.of(scopeA(), scopeB()), List.of("device_id_hash"), List.of(), List.of(), List.of(),
                breakdown(recurrenceFor(2)));
        assertThatThrownBy(() -> service.validateCandidateSummary(retired, fixedScopes())).isInstanceOf(FastApiResponseContractException.class);
    }

    @Test
    void coclusterRelationshipMustSpanTwoPerformancesAndStrongRelationsMustBeScoreSignals() {
        var candidate = candidate(1, 2, List.of("ip_window:RESIDENTIAL"), List.of(), recurrenceFor(2));
        var cocluster = new MultiEventAnalysisDto.RelationshipSummary("rel-1", "stable_account_cocluster", 2, 2, List.of(scopeA(), scopeB()));
        assertThatCode(() -> service.validateRelationshipSummary(candidate, cocluster, fixedScopes())).doesNotThrowAnyException();

        var onePerformance = new MultiEventAnalysisDto.RelationshipSummary("rel-2", "stable_account_cocluster", 2, 1, List.of(scopeA()));
        assertThatThrownBy(() -> service.validateRelationshipSummary(candidate, onePerformance, fixedScopes()))
                .isInstanceOf(FastApiResponseContractException.class);

        var unknownType = new MultiEventAnalysisDto.RelationshipSummary("rel-3", "payment_hash", 2, 2, List.of(scopeA(), scopeB()));
        assertThatThrownBy(() -> service.validateRelationshipSummary(candidate, unknownType, fixedScopes()))
                .isInstanceOf(FastApiResponseContractException.class);

        assertThat(MultiEventInvestigationService.relationshipSignalsConsistent(Set.of("stable_account_cocluster"), candidate)).isTrue();
        assertThat(MultiEventInvestigationService.relationshipSignalsConsistent(Set.of("device_id_hash"), candidate)).isFalse();
        assertThat(MultiEventInvestigationService.relationshipSignalsConsistent(Set.of(), candidate)).isFalse();
        var device = candidate(1, 2, List.of("device_id_hash", "payment_hash"), List.of(), recurrenceFor(2));
        assertThat(MultiEventInvestigationService.relationshipSignalsConsistent(Set.of("device_id_hash", "stable_account_cocluster"), device)).isTrue();
    }

    @Test
    void retiredScoreVersionRunsAreReadOnlyAndCannotBeRetried() {
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("from td_multi_event_investigation_runs r")) return List.of(run());
            if (sql.contains("select distinct event_id")) return List.of(EVENT);
            return List.of();
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        when(jdbc.queryForObject(contains("select score_version"), eq(String.class), any(Object[].class)))
                .thenReturn(MultiEventInvestigationService.RETIRED_SCORE_VERSION);

        assertThatThrownBy(() -> service.retry(EVENT, UUID.fromString("00000000-0000-4000-8000-000000000030")))
                .isInstanceOf(CaseApiException.class).extracting("code").isEqualTo("SCORE_VERSION_RETIRED");
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    /** Data의 int(purchased_at.timestamp()) // 600 으로 계산한 경계 시각 고정 벡터입니다. */
    @ParameterizedTest
    @CsvSource({
            "2026-01-01T00:09:59.999999,2945376",
            "2026-01-01T00:10:00,2945377",
            "2026-01-01T00:19:59,2945377",
            "2026-01-01T00:20:00,2945378",
            "1970-01-01T00:09:59,0",
            "2026-11-25T23:59:59.500000,2992751"
    })
    void ipWindowBucketMatchesTheDataTokenBoundary(String utc, long expected) {
        assertThat(MultiEventInvestigationService.ipWindowBucket(LocalDateTime.parse(utc))).isEqualTo(expected);
        assertThat(MultiEventInvestigationService.IP_WINDOW_BUCKET_SQL.formatted("r.purchased_at"))
                .isEqualTo("(timestampdiff(second,'1970-01-01 00:00:00',r.purchased_at) div 600)");
    }

    private void stubCreate(List<MultiEventInvestigationService.Scope> heads, String... keyVersions) {
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            RowMapper<?> mapper = invocation.getArgument(1);
            if (sql.contains("from td_bulk_ledger_publication_heads h")) return heads;
            if (sql.contains("group by version_id,hmac_purpose,hmac_key_version")) {
                List<Object> rows = new ArrayList<>();
                for (int index = 0; index < keyVersions.length && index < heads.size(); index++) {
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString(1)).thenReturn(heads.get(index).versionId().toString());
                    when(rs.getString(2)).thenReturn("TRACK1_CONNECTION");
                    when(rs.getString(3)).thenReturn(keyVersions[index]);
                    rows.add(mapper.mapRow(rs, index));
                }
                return rows;
            }
            return List.of();
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    private static MultiEventInvestigationService.CreateRequest request() {
        OffsetDateTime end = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1);
        return new MultiEventInvestigationService.CreateRequest(List.of(EVENT), null, end.minusDays(1), end);
    }

    private static MultiEventInvestigationService.Scope scope(UUID performance, UUID version, String supplier) {
        return new MultiEventInvestigationService.Scope(EVENT, performance, version, "event-1", "batch-" + performance, supplier, null);
    }

    private static double recurrenceFor(int performances) { return Math.min((performances - 1) / 3.0, 1.0); }

    private static MultiEventAnalysisDto.ScoreBreakdown breakdown(double recurrence) {
        double q = 0.5, l = 0.5, g = 0.68, b = 0.5 * q + 0.5 * recurrence;
        return new MultiEventAnalysisDto.ScoreBreakdown(l, g, b, q, recurrence, Math.round(l * g * b * 10_000.0) / 10_000.0, "NEUTRAL_NO_POLICY", "UNKNOWN");
    }

    private static MultiEventAnalysisDto.CandidateSummary candidate(int events, int performances, List<String> available, List<String> unavailable, double recurrence) {
        var score = breakdown(recurrence);
        String band = score.score() >= .40 ? "HIGH" : score.score() >= .20 ? "MEDIUM" : "LOW";
        return new MultiEventAnalysisDto.CandidateSummary("multi-" + "c".repeat(64), band, MultiEventInvestigationService.SCORE_VERSION,
                "projection-v2.2-exp2-ipwindow-softhub", 2, events, performances, 2, List.of(scopeA(), scopeB()), available, List.of(), unavailable,
                List.of("NORMAL_SHARED_POSSIBLE"), score);
    }

    private static MultiEventAnalysisDto.LedgerScope scopeA() { return new MultiEventAnalysisDto.LedgerScope("event-1", PERFORMANCE_A, VERSION_A, "batch-a"); }
    private static MultiEventAnalysisDto.LedgerScope scopeB() { return new MultiEventAnalysisDto.LedgerScope("event-1", PERFORMANCE_B, VERSION_B, "batch-b"); }

    private static Map<String, MultiEventAnalysisDto.LedgerScope> fixedScopes() {
        return Map.of("event-1|" + PERFORMANCE_A + "|" + VERSION_A + "|batch-a", scopeA(),
                "event-1|" + PERFORMANCE_B + "|" + VERSION_B + "|batch-b", scopeB());
    }

    private static String contains(String value) { return org.mockito.ArgumentMatchers.contains(value); }

    private static MultiEventInvestigationService.Run run() {
        OffsetDateTime now = OffsetDateTime.parse("2026-11-25T00:00:00Z");
        return new MultiEventInvestigationService.Run(UUID.fromString("00000000-0000-4000-8000-000000000030"), "RETRYABLE", "DATA_UNAVAILABLE",
                "d".repeat(64), MultiEventInvestigationService.RETIRED_SCORE_VERSION, "projection-v2.2-exp2-ipwindow-softhub", null,
                now, now.minusDays(2), now.minusDays(1), now.minusDays(1), now, null);
    }
}
