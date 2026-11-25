package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.persistence.BulkLedgerImportErrorRepository;
import com.ticket.defender_core.adapter.out.persistence.BulkLedgerImportJobRepository;
import com.ticket.defender_core.adapter.out.persistence.BulkLedgerImportScopeRepository;
import com.ticket.defender_core.domain.BulkLedgerImportError;
import com.ticket.defender_core.domain.BulkLedgerImportJob;
import com.ticket.defender_core.domain.BulkLedgerImportScope;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** v3 원본 수신 운영 조건, 업로드 원본 삭제와 재시도 기록. */
class BulkLedgerRawIntakeWorkerTest {
    private static final String DIGEST = "a".repeat(64);
    private static final String HMAC_KEY = "synthetic-only-master-key-material-that-is-long-enough";

    @Test
    void v3IsRejectedWithoutReadingTheObjectWhenRawIntakeIsClosedAndTheUploadIsDeleted() {
        Fixture fixture = fixture("v3", v3Csv(), false);

        fixture.worker().process(fixture.job().getId());

        assertThat(fixture.job().getStatus()).isEqualTo(BulkLedgerImportJob.Status.REJECTED);
        verify(fixture.storage(), never()).openVersion(anyString(), anyString());
        verify(fixture.deletions()).deleteUpload(fixture.job().getId(), fixture.job().getTenantId(), BulkLedgerUploadDeletionService.Reason.STAGING_REJECTED);
        ArgumentCaptor<BulkLedgerImportError> error = ArgumentCaptor.forClass(BulkLedgerImportError.class);
        verify(fixture.errors()).save(error.capture());
        assertThat(error.getValue().getCode()).isEqualTo("BULK_V3_RAW_INTAKE_DISABLED");
    }

    @Test
    void acceptedV3StagesOnlyTokensAndDeletesTheUploadAfterStaging() {
        Fixture fixture = fixture("v3", v3Csv(), true);

        fixture.worker().process(fixture.job().getId());

        assertThat(fixture.job().getStatus()).isEqualTo(BulkLedgerImportJob.Status.READY_TO_PUBLISH);
        assertThat(fixture.staged()).singleElement().satisfies(row -> {
            assertThat(row.signals().ipHmac()).matches("[0-9a-f]{64}");
            assertThat(row.signals().phoneSource()).isEqualTo("RECIPIENT");
            assertThat(row.toString()).doesNotContain("192.0.2.44", "000-0000-0044", "가상시 가상구 모의로 44");
        });
        verify(fixture.deletions()).deleteUpload(fixture.job().getId(), fixture.job().getTenantId(), BulkLedgerUploadDeletionService.Reason.STAGING_COMPLETED);
    }

    @Test
    void v1AndV2AlsoDeleteTheirUploadAfterFinalStagingWithoutChangingTransitions() {
        for (String schema : List.of("v1", "v2")) {
            Fixture fixture = fixture(schema, "v1".equals(schema) ? v1Csv() : v2Csv(), false);
            fixture.worker().process(fixture.job().getId());
            assertThat(fixture.job().getStatus()).isEqualTo(BulkLedgerImportJob.Status.READY_TO_PUBLISH);
            verify(fixture.deletions()).deleteUpload(fixture.job().getId(), fixture.job().getTenantId(), BulkLedgerUploadDeletionService.Reason.STAGING_COMPLETED);
        }
    }

    @Test
    void finalRowRejectionDeletesButRetryableFailureKeepsTheUploadForRetry() {
        Fixture rejected = fixture("v3", v3Csv().replace("192.0.2.44", "2001:db8::44"), true);
        rejected.worker().process(rejected.job().getId());
        assertThat(rejected.job().getStatus()).isEqualTo(BulkLedgerImportJob.Status.REJECTED);
        verify(rejected.deletions()).deleteUpload(rejected.job().getId(), rejected.job().getTenantId(), BulkLedgerUploadDeletionService.Reason.STAGING_REJECTED);

        Fixture retryable = fixture("v3", v3Csv(), true);
        when(retryable.storage().openVersion(anyString(), anyString())).thenThrow(new BulkLedgerUploadStorage.Unavailable("synthetic outage"));
        retryable.worker().process(retryable.job().getId());
        assertThat(retryable.job().getStatus()).isEqualTo(BulkLedgerImportJob.Status.FAILED_RETRYABLE);
        verify(retryable.deletions(), never()).deleteUpload(any(), any(), any());
    }

    @Test
    void deletionRetriesThreeTimesRecordsFailureAndNeverThrows() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        BulkLedgerUploadStorage storage = mock(BulkLedgerUploadStorage.class);
        doThrow(new BulkLedgerUploadStorage.Unavailable("synthetic delete denied")).when(storage).deleteAllVersions(anyString());
        var service = new BulkLedgerUploadDeletionService(jdbc, storage, policy(true, true, true), true);
        UUID importId = UUID.randomUUID(), tenant = UUID.randomUUID();

        assertThat(service.deleteUpload(importId, tenant, BulkLedgerUploadDeletionService.Reason.STAGING_COMPLETED)).isFalse();

        verify(storage, times(3)).deleteAllVersions("bulk-ledger-private/" + importId + "/upload.csv");
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("td_bulk_import_upload_deletions"), args.capture());
        assertThat(args.getValue()).contains("FAILED", 3, "UPLOAD_DELETE_UNAVAILABLE", "STAGING_COMPLETED");
    }

    @Test
    void deletionSucceedsOnTheSecondAttemptAndRecordFailureDoesNotPropagate() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        BulkLedgerUploadStorage storage = mock(BulkLedgerUploadStorage.class);
        doThrow(new BulkLedgerUploadStorage.Unavailable("transient")).doNothing().when(storage).deleteAllVersions(anyString());
        var service = new BulkLedgerUploadDeletionService(jdbc, storage, policy(true, true, true), true);

        assertThat(service.deleteUpload(UUID.randomUUID(), UUID.randomUUID(), BulkLedgerUploadDeletionService.Reason.STAGING_REJECTED)).isTrue();
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), args.capture());
        assertThat(args.getValue()).contains("DELETED", 2);

        when(jdbc.update(anyString(), any(Object[].class))).thenThrow(new IllegalStateException("db down"));
        assertThatCode(() -> service.deleteUpload(UUID.randomUUID(), UUID.randomUUID(), BulkLedgerUploadDeletionService.Reason.RETENTION_EXPIRED))
                .doesNotThrowAnyException();
    }

    @Test
    void cleanupIsSkippedWhenDisabledAndUsesTheRetentionCutoffWhenEnabled() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        BulkLedgerUploadStorage storage = mock(BulkLedgerUploadStorage.class);
        new BulkLedgerUploadDeletionService(jdbc, storage, policy(false, false, true), false).cleanupExpired();
        verify(jdbc, never()).query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class));

        new BulkLedgerUploadDeletionService(jdbc, storage, policy(true, true, true), true).cleanupExpired();
        verify(jdbc).query(contains("td_bulk_import_upload_deletions"), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class));
    }

    @Test
    void rawIntakePolicyIsClosedByDefaultRequiresCleanupAndChecksDeletePermission() {
        BulkLedgerRawIntakePolicy closed = policy(false, false, true);
        assertThat(closed.v3Accepted()).isFalse();
        assertThatThrownBy(closed::requireV3Accepted).isInstanceOf(CaseApiException.class).extracting("code").isEqualTo("BULK_V3_RAW_INTAKE_DISABLED");

        assertThatThrownBy(() -> policy(true, false, true)).isInstanceOf(IllegalStateException.class).hasMessageContaining("cleanup");

        BulkLedgerRawIntakePolicy unverified = policy(true, true, false);
        assertThat(unverified.v3Accepted()).isFalse();
        assertThatThrownBy(unverified::requireV3Accepted).isInstanceOf(CaseApiException.class)
                .extracting("code").isEqualTo("BULK_V3_DELETE_PERMISSION_UNVERIFIED");

        BulkLedgerUploadStorage failing = mock(BulkLedgerUploadStorage.class);
        when(failing.verifyDeletePermission()).thenThrow(new IllegalStateException("probe failed"));
        BulkLedgerRawIntakePolicy throwing = new BulkLedgerRawIntakePolicy(true, true, Duration.ofHours(24), failing);
        throwing.verifyAtStartup();
        assertThat(throwing.v3Accepted()).isFalse();

        BulkLedgerRawIntakePolicy open = policy(true, true, true);
        assertThat(open.v3Accepted()).isTrue();
        assertThatCode(open::requireV3Accepted).doesNotThrowAnyException();

        assertThatThrownBy(() -> new BulkLedgerRawIntakePolicy(false, false, Duration.ofHours(25), mock(BulkLedgerUploadStorage.class)).verifyAtStartup())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void kmsKeySourceFailsClosedWithoutAnImplementation() {
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<Track1SignalKeySource> none = mock(org.springframework.beans.factory.ObjectProvider.class);
        var factory = new BulkLedgerRawSignalHasherFactory(mock(SyntheticReservationImportGate.class), none, "KMS", List.of());
        assertThatThrownBy(factory::hashers).isInstanceOf(CaseApiException.class).extracting("code").isEqualTo("TRACK1_SIGNAL_KEY_UNAVAILABLE");
        assertThatThrownBy(() -> new BulkLedgerRawSignalHasherFactory(mock(SyntheticReservationImportGate.class), none, "FILE", List.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    private static String contains(String value) { return org.mockito.ArgumentMatchers.contains(value); }

    private static BulkLedgerRawIntakePolicy policy(boolean enabled, boolean cleanup, boolean deletable) {
        BulkLedgerUploadStorage storage = mock(BulkLedgerUploadStorage.class);
        when(storage.verifyDeletePermission()).thenReturn(deletable);
        BulkLedgerRawIntakePolicy policy = new BulkLedgerRawIntakePolicy(enabled, cleanup, Duration.ofHours(24), storage);
        policy.verifyAtStartup();
        return policy;
    }

    private static Fixture fixture(String schema, String csv, boolean v3Open) {
        UUID tenant = UUID.randomUUID(), event = UUID.randomUUID(), performance = UUID.randomUUID();
        String resolved = csv.replace("{event}", event.toString()).replace("{performance}", performance.toString());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        BulkLedgerImportJob job = new BulkLedgerImportJob(tenant, UUID.randomUUID(), DIGEST, DIGEST, schema, "synthetic", "batch-1",
                BulkLedgerImportJob.Mode.FULL_SNAPSHOT, BulkLedgerImportJob.Format.CSV, DIGEST, resolved.getBytes(StandardCharsets.UTF_8).length, 1, true, now);
        job.beginUpload(DIGEST, now.plusMinutes(1), now);
        job.completeUpload("object-version-1", now);
        BulkLedgerImportScope scope = "v1".equals(schema)
                ? new BulkLedgerImportScope(UUID.randomUUID(), tenant, event, performance, now, 1, null, null)
                : new BulkLedgerImportScope(UUID.randomUUID(), tenant, event, performance, now, 1, null, "cursor-1", null, 1L);

        BulkLedgerImportJobRepository jobs = mock(BulkLedgerImportJobRepository.class);
        BulkLedgerImportScopeRepository scopes = mock(BulkLedgerImportScopeRepository.class);
        BulkLedgerImportErrorRepository errors = mock(BulkLedgerImportErrorRepository.class);
        SyntheticReservationImportGate gate = mock(SyntheticReservationImportGate.class);
        BulkLedgerUploadStorage storage = mock(BulkLedgerUploadStorage.class);
        BulkLedgerStagingWriter staging = mock(BulkLedgerStagingWriter.class);
        BulkLedgerStagingWriter.Session session = mock(BulkLedgerStagingWriter.Session.class);
        BulkLedgerCandidatePreparationService candidates = mock(BulkLedgerCandidatePreparationService.class);
        BulkLedgerRawSignalHasherFactory factory = mock(BulkLedgerRawSignalHasherFactory.class);
        BulkLedgerUploadDeletionService deletions = mock(BulkLedgerUploadDeletionService.class);
        List<BulkLedgerCsvStagingParser.ParsedRow> staged = new ArrayList<>();

        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        when(scopes.findByJobIdOrderByEventIdAscPerformanceIdAsc(job.getId())).thenReturn(List.of(scope));
        when(gate.requireSyntheticScope(any(), any())).thenReturn(UUID.randomUUID());
        when(gate.referenceHasher()).thenReturn(new TenantPurposeReferenceHasher(HMAC_KEY, "v1"));
        when(gate.referenceHasher("BL02_TICKET_REFERENCE")).thenReturn(new TenantPurposeReferenceHasher(HMAC_KEY, "BL02_TICKET_REFERENCE", "v1"));
        when(gate.referenceHasher("BL02_ACCOUNT_REFERENCE")).thenReturn(new TenantPurposeReferenceHasher(HMAC_KEY, "BL02_ACCOUNT_REFERENCE", "v1"));
        when(factory.hashers()).thenReturn(BulkLedgerCsvStagingParserV3Test.hashers(List.of()));
        when(storage.openVersion(anyString(), anyString())).thenReturn(new ByteArrayInputStream(resolved.getBytes(StandardCharsets.UTF_8)));
        when(staging.open(eq(job.getId()), eq(tenant), any(Long.class))).thenReturn(session);
        doAnswer(invocation -> { staged.add(invocation.getArgument(0)); return null; }).when(session).accept(any());
        when(staging.countForImport(job.getId())).thenReturn(1L);
        when(staging.scopeCountsForImport(job.getId())).thenReturn(Map.of(event + ":" + performance, 1L));

        BulkLedgerRawIntakePolicy policy = policy(v3Open, v3Open, true);
        var worker = new BulkLedgerImportWorker(jobs, scopes, errors, gate, storage, staging, candidates, immediateTransactions(), policy, factory, deletions);
        return new Fixture(worker, job, storage, deletions, errors, staged);
    }

    private static TransactionTemplate immediateTransactions() {
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactions).executeWithoutResult(any());
        return transactions;
    }

    private static String v1Csv() {
        return "event_id,performance_id,reservation_reference,ticket_reference,account_reference,purchased_at,ticket_quantity\n"
                + "{event},{performance},reservation-1,ticket-1,account-1,2026-11-13T10:00:00Z,1\n";
    }

    private static String v2Csv() {
        return "event_id,performance_id,reservation_reference,ticket_reference,account_reference,purchased_at,ticket_quantity,quantity_unit,ticket_status,source_version\n"
                + "{event},{performance},reservation-1,ticket-1,account-1,2026-11-13T10:00:00Z,1,TICKET,ACTIVE,source-v1\n";
    }

    private static String v3Csv() {
        return "event_id,performance_id,reservation_reference,ticket_reference,account_reference,purchased_at,ticket_quantity,quantity_unit,ticket_status,source_version,"
                + "ip_reference,recipient_phone,delivery_address\n"
                + "{event},{performance},reservation-1,ticket-1,account-1,2026-11-13T10:00:00Z,1,TICKET,ACTIVE,source-v1,192.0.2.44,000-0000-0044,가상시 가상구 모의로 44\n";
    }

    private record Fixture(BulkLedgerImportWorker worker, BulkLedgerImportJob job, BulkLedgerUploadStorage storage,
                           BulkLedgerUploadDeletionService deletions, BulkLedgerImportErrorRepository errors,
                           List<BulkLedgerCsvStagingParser.ParsedRow> staged) { }
}
