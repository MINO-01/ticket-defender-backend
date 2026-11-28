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
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    private final AtomicReference<String> familyPageSql = new AtomicReference<>();
    private final AtomicReference<String> baselineSql = new AtomicReference<>();
    private final AtomicReference<Object[]> baselineArguments = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        service = new MultiEventInvestigationService(jdbc, tx, access, data, true, "supplier-a");
    }

    @Test
    void largeArtifactOmitsCancelledRowsWhenTransferIsDisabled() throws Exception {
        stubArtifactRows();

        List<String> lines = writeArtifactRows(false);

        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst()).doesNotContain("CANCELLED_TICKET", "cancelledTicket", "rawTicketNumber");
        verify(jdbc, times(1)).query(any(PreparedStatementCreator.class), any(RowCallbackHandler.class));
    }

    @Test
    void largeArtifactWritesCancelledRowsAsSeparatePseudonymousRecordsWhenEnabled() throws Exception {
        stubArtifactRows();

        List<String> lines = writeArtifactRows(true);

        assertThat(lines).hasSize(2);
        assertThat(lines.getFirst()).doesNotContain("CANCELLED_TICKET", "cancelledTicket");
        assertThat(lines.get(1)).contains("\"recordType\":\"CANCELLED_TICKET\"", "\"cancelledTicket\"",
                "\"accountHmac\"", "\"ticketHmac\"", "\"cancelledAt\"", "BALCONY", "R1", "12")
                .doesNotContain("rawTicketNumber");
        verify(jdbc, times(2)).query(any(PreparedStatementCreator.class), any(RowCallbackHandler.class));
    }

    @Test
    void cancelledArtifactUsesCancellationTimeForAnalysisWindow() throws Exception {
        var field = MultiEventInvestigationService.class.getDeclaredField("ARTIFACT_CANCELLED_TICKET_SQL");
        field.setAccessible(true);
        String sql = (String) field.get(null);

        assertThat(sql).contains("r.cancelled_at>=? and r.cancelled_at<?")
                .doesNotContain("r.purchased_at>=?", "r.purchased_at<?");
    }

    private List<String> writeArtifactRows(boolean cancellationTransferEnabled) throws Exception {
        service = new MultiEventInvestigationService(jdbc, tx, access, data, true, "supplier-a", cancellationTransferEnabled);
        var scope = new MultiEventInvestigationService.Scope(EVENT_A,
                UUID.fromString("00000000-0000-4000-8000-000000000041"),
                UUID.fromString("00000000-0000-4000-8000-000000000051"), "event-a", "batch-a", "supplier-a", null);
        var claim = new MultiEventInvestigationService.Claim(RUN_ID, TENANT, 1, "RUNNING", null, null,
                null, null, null, 0, 0);
        Class<?> runInputType = Class.forName(MultiEventInvestigationService.class.getName() + "$RunInput");
        Constructor<?> constructor = runInputType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object input = constructor.newInstance(TENANT, "digest", "supplier-a", "TRACK1_CONNECTION", "key-v1",
                MultiEventInvestigationService.SCORE_VERSION, "projection-v1",
                OffsetDateTime.parse("2026-11-25T00:00:00Z"), OffsetDateTime.parse("2026-11-24T00:00:00Z"),
                OffsetDateTime.parse("2026-11-26T00:00:00Z"));
        Method writer = MultiEventInvestigationService.class.getDeclaredMethod("writeArtifactRows",
                MultiEventInvestigationService.Claim.class, List.class, runInputType, Path.class, MessageDigest.class);
        writer.setAccessible(true);

        Path path = Files.createTempFile("td-mei-cancellation-test-", ".ndjson");
        try {
            Object result = writer.invoke(service, claim, List.of(scope), input, path, MessageDigest.getInstance("SHA-256"));
            assertThat(org.springframework.test.util.ReflectionTestUtils.getField(result, "recordCount"))
                    .isEqualTo(cancellationTransferEnabled ? 2L : 1L);
            assertThat(org.springframework.test.util.ReflectionTestUtils.getField(result, "cancelledTicketRecordCount"))
                    .isEqualTo(cancellationTransferEnabled ? 1 : 0);
            return Files.readAllLines(path);
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private void stubArtifactRows() throws Exception {
        doAnswer(invocation -> {
            PreparedStatementCreator creator = invocation.getArgument(0);
            RowCallbackHandler callback = invocation.getArgument(1);
            var sql = new AtomicReference<String>();
            Connection connection = mock(Connection.class);
            PreparedStatement statement = mock(PreparedStatement.class);
            when(connection.prepareStatement(anyString(), anyInt(), anyInt())).thenAnswer(prepare -> {
                sql.set(prepare.getArgument(0));
                return statement;
            });
            creator.createPreparedStatement(connection);
            if (sql.get().contains("r.ticket_status='ACTIVE'")) {
                callback.processRow(activeArtifactRow());
            } else if (sql.get().contains("r.ticket_status in ('CANCELLED','REFUNDED')")) {
                callback.processRow(cancelledArtifactRow());
            } else {
                throw new AssertionError("Unexpected artifact query");
            }
            return 1;
        }).when(jdbc).query(any(PreparedStatementCreator.class), any(RowCallbackHandler.class));
    }

    private static ResultSet activeArtifactRow() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString(1)).thenReturn("a".repeat(64));
        when(row.getString(2)).thenReturn("b".repeat(64));
        when(row.getTimestamp(3)).thenReturn(Timestamp.valueOf("2026-11-25 10:00:00"));
        when(row.getString(4)).thenReturn("key-v1");
        when(row.getString(5)).thenReturn("source-v1");
        when(row.getString(6)).thenReturn("c".repeat(64));
        when(row.getString(13)).thenReturn("TRACK1_CONNECTION");
        when(row.getString(14)).thenReturn("key-v1");
        when(row.getString(15)).thenReturn("source-v1");
        return row;
    }

    private static ResultSet cancelledArtifactRow() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString(1)).thenReturn("d".repeat(64));
        when(row.getString(2)).thenReturn("e".repeat(64));
        when(row.getString(3)).thenReturn("key-v1");
        when(row.getString(4)).thenReturn("source-v1");
        when(row.getString(5)).thenReturn("BALCONY");
        when(row.getString(6)).thenReturn("R1");
        when(row.getString(7)).thenReturn("12");
        when(row.getTimestamp(8)).thenReturn(Timestamp.valueOf("2026-11-25 11:00:00"));
        return row;
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
    void legacyCandidatesWithoutFamilyMetadataRemainVisibleAsSingletonFamilies() {
        when(access.event(EVENT_A, ROLES)).thenReturn(scope(EVENT_A));
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("from td_multi_event_investigation_runs r")) return List.of(run());
            if (sql.contains("select distinct event_id")) return List.of(EVENT_A);
            if (sql.contains("group by coalesce(family_id,candidate_id)")) {
                familyPageSql.set(sql);
                return List.of();
            }
            return List.of();
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));

        var result = service.candidateFamilies(EVENT_A, RUN_ID, 0, 20);

        assertThat(result.items()).isEmpty();
        assertThat(familyPageSql.get()).contains("coalesce(family_id,candidate_id)");
        assertThat(familyPageSql.get()).doesNotContain("family_id is not null");
    }

    @Test
    void candidateMemberRelationshipAndSourceRowsStayHiddenUntilTheDataJobCompletes() {
        when(access.event(EVENT_A, ROLES)).thenReturn(scope(EVENT_A));
        when(access.event(EVENT_B, ROLES)).thenReturn(scope(EVENT_B));
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("from td_multi_event_investigation_runs r")) return List.of(run("DATA_PENDING"));
            if (sql.contains("select distinct event_id")) return List.of(EVENT_A, EVENT_B);
            return List.of();
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));

        assertUnavailable(() -> service.candidates(EVENT_A, RUN_ID, 0, 20));
        assertUnavailable(() -> service.members(EVENT_A, RUN_ID, "multi-" + "a".repeat(64), 0, 20));
        assertUnavailable(() -> service.relationships(EVENT_A, RUN_ID, "multi-" + "a".repeat(64), 0, 20));
        assertUnavailable(() -> service.scopes(EVENT_A, RUN_ID));

        verify(jdbc, never()).queryForObject(anyString(), any(Class.class), any(Object[].class));
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

    @Test
    void ledgerVersionDigestUsesCanonicalLexicalUuidOrderingAcrossHighBitBoundary() {
        UUID event7f = UUID.fromString("7fffffff-0000-4000-8000-000000000001");
        UUID event80 = UUID.fromString("80000000-0000-4000-8000-000000000001");
        UUID perf7f = UUID.fromString("7fffffff-0000-4000-8000-000000000002");
        UUID perf80 = UUID.fromString("80000000-0000-4000-8000-000000000002");
        UUID version7f = UUID.fromString("7fffffff-0000-4000-8000-000000000003");
        UUID version80 = UUID.fromString("80000000-0000-4000-8000-000000000003");
        var scopes = List.of(
                new MultiEventInvestigationService.Scope(event80, perf80, version80, "event-b", "batch-b", "supplier-a", null),
                new MultiEventInvestigationService.Scope(event7f, perf7f, version7f, "event-a", "batch-a", "supplier-a", null));
        String expectedRows = event7f + "|" + perf7f + "|" + version7f + "\n"
                + event80 + "|" + perf80 + "|" + version80;

        assertThat(MultiEventInvestigationService.canonicalLedgerVersionRows(scopes)).isEqualTo(expectedRows);
        assertThat(CandidatePipelineSupport.sha256("MEI_LEDGER_VERSION_SET",
                MultiEventInvestigationService.canonicalLedgerVersionRows(scopes)))
                .isEqualTo(CandidatePipelineSupport.sha256("MEI_LEDGER_VERSION_SET", expectedRows));
    }

    @Test
    void relationshipSummaryRejectsNarrowerAccountCountBeforeEvidencePersistence() {
        var performanceA = UUID.fromString("00000000-0000-4000-8000-000000000041");
        var performanceB = UUID.fromString("00000000-0000-4000-8000-000000000042");
        var versionA = UUID.fromString("00000000-0000-4000-8000-000000000051");
        var versionB = UUID.fromString("00000000-0000-4000-8000-000000000052");
        var scopeA = new MultiEventAnalysisDto.LedgerScope("event-a", performanceA, versionA, "batch-a");
        var scopeB = new MultiEventAnalysisDto.LedgerScope("event-b", performanceB, versionB, "batch-b");
        var candidate = new MultiEventAnalysisDto.CandidateSummary("multi-" + "c".repeat(64), "HIGH",
                "score-v2-exp2-multi-performance-recurrence", "projection-v2.2-exp2-ipwindow-softhub",
                3, 2, 2, 1, List.of(scopeA, scopeB), List.of("refund_account_hash"), List.of(), List.of(), List.of(), null,
                "multi-"+"c".repeat(64),null,0,0.5,List.of());
        var narrowerRelation = new MultiEventAnalysisDto.RelationshipSummary("relation-refund", "refund_account_hash",
                2, 2, List.of(scopeA, scopeB));
        var fixedScopes = java.util.Map.of(
                "event-a|" + performanceA + "|" + versionA + "|batch-a", scopeA,
                "event-b|" + performanceB + "|" + versionB + "|batch-b", scopeB);

        assertThatThrownBy(() -> service.validateRelationshipSummary(candidate, narrowerRelation, fixedScopes))
                .isInstanceOf(com.ticket.defender_core.adapter.out.api.FastApiResponseContractException.class);

        verifyNoInteractions(jdbc, data);
    }

    @Test
    void onlyTheExplicitDataQueueFull429IsClassifiedAsRetryableOverload() {
        var full = org.springframework.web.client.HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS,
                "Too Many Requests", new HttpHeaders(),
                "{\"detail\":{\"code\":\"MULTI_EVENT_JOB_QUEUE_FULL\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.charset.StandardCharsets.UTF_8);
        var unrelated429 = org.springframework.web.client.HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS,
                "Too Many Requests", new HttpHeaders(),
                "{\"detail\":{\"code\":\"OTHER_RATE_LIMIT\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.charset.StandardCharsets.UTF_8);

        assertThat(service.isDataQueueFull(full)).isTrue();
        assertThat(service.isDataQueueFull(unrelated429)).isFalse();
    }

    @Test
    void repeatedQueueFullDeferralsKeepTheFixedArtifactAndDoNotSpendAttemptBudget() {
        String artifactId = "mei-fixed-artifact";
        String idempotencyKey = MultiEventInvestigationService.dataJobIdempotencyKey(RUN_ID);
        var claim = new MultiEventInvestigationService.Claim(RUN_ID, TENANT, 5, "RUNNING", null,
                artifactId, "s3-version-1", "a".repeat(64), 50_001L, 0, 1);
        AtomicReference<String> updateSql = new AtomicReference<>();
        doAnswer(invocation -> {
            java.util.function.Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(new SimpleTransactionStatus());
            return null;
        }).when(tx).executeWithoutResult(any());
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            updateSql.set(invocation.getArgument(0));
            return 1;
        });

        for (int retry = 0; retry < 4; retry++) {
            service.markQueueFullPending(claim);
            assertThat(MultiEventInvestigationService.shouldIncrementAttempt("DATA_PENDING", null, artifactId)).isFalse();
            assertThat(MultiEventInvestigationService.dataJobIdempotencyKey(RUN_ID)).isEqualTo(idempotencyKey);
            assertThat(updateSql.get()).contains("status='DATA_PENDING'", "failure_code=null", "lease_owner=null")
                    .doesNotContain("attempt_count", "artifact_id=");
        }
    }

    @Test
    void dataRetryQueueFullDoesNotIncrementAttemptsBeforeAcceptance() {
        UUID dataJobId = UUID.fromString("00000000-0000-4000-8000-000000000040");
        var claim = new MultiEventInvestigationService.Claim(RUN_ID, TENANT, 6, "RUNNING", dataJobId,
                "mei-fixed-artifact", "s3-version-1", "a".repeat(64), 50_001L, 0, 1);
        var queueFull = org.springframework.web.client.HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS,
                "Too Many Requests", new HttpHeaders(),
                "{\"detail\":{\"code\":\"MULTI_EVENT_JOB_QUEUE_FULL\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.charset.StandardCharsets.UTF_8);
        when(data.retryMultiEventJob(dataJobId)).thenThrow(queueFull);

        assertThatThrownBy(() -> service.retryDataJob(claim)).isSameAs(queueFull);

        verify(tx, never()).executeWithoutResult(any());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void acceptedDataRetryIncrementsAttemptsOnlyWithFencedPendingTransition() {
        UUID dataJobId = UUID.fromString("00000000-0000-4000-8000-000000000040");
        var claim = new MultiEventInvestigationService.Claim(RUN_ID, TENANT, 6, "RUNNING", dataJobId,
                "mei-fixed-artifact", "s3-version-1", "a".repeat(64), 50_001L, 0, 1);
        AtomicReference<String> updateSql = new AtomicReference<>();
        when(data.retryMultiEventJob(dataJobId)).thenReturn(
                new MultiEventAnalysisDto.ArtifactAccepted(dataJobId, "QUEUED", false));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            updateSql.set(invocation.getArgument(0));
            return 1;
        });
        doAnswer(invocation -> {
            java.util.function.Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(new SimpleTransactionStatus());
            return null;
        }).when(tx).executeWithoutResult(any());

        service.retryDataJob(claim);

        assertThat(updateSql.get()).contains("status='DATA_PENDING'", "attempt_count=attempt_count+1",
                "fence_token=?", "lease_owner=?");
    }

    private void assertDenied(Runnable action) {
        assertThatThrownBy(() -> action.run())
                .isInstanceOf(CaseApiException.class)
                .extracting("code").isEqualTo("RESOURCE_NOT_FOUND");
    }

    private void assertUnavailable(Runnable action) {
        assertThatThrownBy(() -> action.run())
                .isInstanceOf(CaseApiException.class)
                .extracting("code").isEqualTo("MULTI_EVENT_RESULTS_NOT_AVAILABLE");
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
        return run("COMPLETED");
    }

    private static MultiEventInvestigationService.Run run(String status) {
        OffsetDateTime now = OffsetDateTime.parse("2026-11-25T00:00:00Z");
        return new MultiEventInvestigationService.Run(RUN_ID, status, null, "d".repeat(64),
                "score-v2-exp2-multi-performance-recurrence", "projection-v2.2-exp2-ipwindow-softhub", "gds-2.6.6",
                now, now.minusDays(2), now.minusDays(1), now.minusDays(1), now,
                "COMPLETED".equals(status) ? now : null);
    }
}
