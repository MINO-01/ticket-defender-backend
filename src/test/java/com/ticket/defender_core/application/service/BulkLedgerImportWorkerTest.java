package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.persistence.BulkLedgerImportErrorRepository;
import com.ticket.defender_core.adapter.out.persistence.BulkLedgerImportJobRepository;
import com.ticket.defender_core.adapter.out.persistence.BulkLedgerImportScopeRepository;
import com.ticket.defender_core.domain.BulkLedgerImportJob;
import com.ticket.defender_core.domain.BulkLedgerImportScope;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class BulkLedgerImportWorkerTest {
    private static final String DIGEST = "a".repeat(64);
    private static final String HMAC_KEY = "synthetic-only-master-key-material-that-is-long-enough";

    @Test
    void candidatePreparationExceptionLeavesCompletedStagingReadyForCandidateOnlyRetry() {
        Fixture fixture = fixture(validCsv());
        doThrow(new IllegalStateException("candidate database unavailable")).when(fixture.candidates()).prepare(fixture.job().getId());

        assertThatCode(() -> fixture.worker().process(fixture.job().getId())).doesNotThrowAnyException();

        assertThat(fixture.job().getStatus()).isEqualTo(BulkLedgerImportJob.Status.READY_TO_PUBLISH);
        verify(fixture.candidates()).prepare(fixture.job().getId());
    }

    @Test
    void successfulCandidatePreparationKeepsStagingCompletionReady() {
        Fixture fixture = fixture(validCsv());

        fixture.worker().process(fixture.job().getId());

        assertThat(fixture.job().getStatus()).isEqualTo(BulkLedgerImportJob.Status.READY_TO_PUBLISH);
        verify(fixture.candidates()).prepare(fixture.job().getId());
    }

    @Test
    void tdUuidActualStagingEncryptsReferencesWhenRevealIsEnabled() {
        InvestigationReferenceCipher cipher = enabledCipher();
        Fixture fixture = fixture(validCsv(), new BulkLedgerCsvStagingParser(cipher));

        fixture.worker().process(fixture.job().getId());

        ArgumentCaptor<BulkLedgerCsvStagingParser.ParsedRow> row = ArgumentCaptor.forClass(BulkLedgerCsvStagingParser.ParsedRow.class);
        verify(fixture.session()).accept(row.capture());
        var parsed = row.getValue();
        assertThat(fixture.job().getScopeKeyType()).isEqualTo("TD_UUID");
        assertThat(parsed.encryptedReferences()).isNotNull();
        var references = cipher.decrypt(fixture.job().getId(), fixture.job().getTenantId(), parsed.eventId(),
                parsed.performanceId(), parsed.rowNumber(), parsed.ticketToken(), parsed.reservationToken(),
                parsed.accountToken(), parsed.encryptedReferences());
        assertThat(references.accountReference()).isEqualTo("account-1");
        assertThat(references.reservationReference()).isEqualTo("reservation-1");
        assertThat(references.ticketReference()).isEqualTo("ticket-1");
    }

    @Test
    void tdUuidActualStagingKeepsCiphertextAbsentWhenRevealIsDisabled() {
        Fixture fixture = fixture(validCsv());

        fixture.worker().process(fixture.job().getId());

        ArgumentCaptor<BulkLedgerCsvStagingParser.ParsedRow> row = ArgumentCaptor.forClass(BulkLedgerCsvStagingParser.ParsedRow.class);
        verify(fixture.session()).accept(row.capture());
        assertThat(row.getValue().encryptedReferences()).isNull();
    }

    @Test
    void stagingValidationFailureStillRejectsBeforeCandidatePreparation() {
        Fixture fixture = fixture("event_id,performance_id\n");

        fixture.worker().process(fixture.job().getId());

        assertThat(fixture.job().getStatus()).isEqualTo(BulkLedgerImportJob.Status.REJECTED);
        verify(fixture.candidates(), never()).prepare(any());
    }

    private static Fixture fixture(String csv) {
        return fixture(csv, new BulkLedgerCsvStagingParser());
    }

    private static Fixture fixture(String csv, BulkLedgerCsvStagingParser parser) {
        UUID tenant = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        UUID performance = UUID.randomUUID();
        String resolvedCsv = csv.replace("{event}", event.toString()).replace("{performance}", performance.toString());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        BulkLedgerImportJob job = new BulkLedgerImportJob(tenant, UUID.randomUUID(), DIGEST, DIGEST,
                "v1", "synthetic", "batch-1", BulkLedgerImportJob.Mode.FULL_SNAPSHOT,
                BulkLedgerImportJob.Format.CSV, DIGEST, resolvedCsv.getBytes(StandardCharsets.UTF_8).length, 1, true, now);
        job.beginUpload(DIGEST, now.plusMinutes(1), now);
        job.completeUpload("object-version-1", now);
        BulkLedgerImportScope scope = new BulkLedgerImportScope(UUID.randomUUID(), tenant, event, performance,
                now, 1, null, null);

        BulkLedgerImportJobRepository jobs = mock(BulkLedgerImportJobRepository.class);
        BulkLedgerImportScopeRepository scopes = mock(BulkLedgerImportScopeRepository.class);
        BulkLedgerImportErrorRepository errors = mock(BulkLedgerImportErrorRepository.class);
        SyntheticReservationImportGate gate = mock(SyntheticReservationImportGate.class);
        BulkLedgerUploadStorage storage = mock(BulkLedgerUploadStorage.class);
        BulkLedgerStagingWriter staging = mock(BulkLedgerStagingWriter.class);
        BulkLedgerStagingWriter.Session session = mock(BulkLedgerStagingWriter.Session.class);
        BulkLedgerCandidatePreparationService candidates = mock(BulkLedgerCandidatePreparationService.class);
        TransactionTemplate transactions = immediateTransactions();

        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        when(scopes.findByJobIdOrderByEventIdAscPerformanceIdAsc(job.getId())).thenReturn(List.of(scope));
        when(gate.requireSyntheticScope(any(), any())).thenReturn(UUID.randomUUID());
        when(gate.referenceHasher()).thenReturn(new TenantPurposeReferenceHasher(HMAC_KEY, "v1"));
        when(gate.referenceHasher("BL02_TICKET_REFERENCE")).thenReturn(new TenantPurposeReferenceHasher(HMAC_KEY, "BL02_TICKET_REFERENCE", "v1"));
        when(gate.referenceHasher("BL02_ACCOUNT_REFERENCE")).thenReturn(new TenantPurposeReferenceHasher(HMAC_KEY, "BL02_ACCOUNT_REFERENCE", "v1"));
        when(storage.openVersion(anyString(), anyString())).thenReturn(new ByteArrayInputStream(resolvedCsv.getBytes(StandardCharsets.UTF_8)));
        when(staging.open(job.getId(), tenant, 1L)).thenReturn(session);
        when(staging.countForImport(job.getId())).thenReturn(1L);
        when(staging.scopeCountsForImport(job.getId())).thenReturn(Map.of(event + ":" + performance, 1L));

        return new Fixture(new BulkLedgerImportWorker(jobs, scopes, errors, gate, storage, staging, candidates, transactions,
                null, null, null, parser, null, null), job, candidates, session);
    }

    private static InvestigationReferenceCipher enabledCipher() {
        var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        String master = java.util.Base64.getEncoder().encodeToString(new byte[32]);
        return new InvestigationReferenceCipher(beans.getBeanProvider(InvestigationRevealKeySource.class), true,
                "SYNTHETIC_CONFIG", master, "synthetic-test-v1");
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

    private static String validCsv() {
        return "event_id,performance_id,reservation_reference,ticket_reference,account_reference,purchased_at,ticket_quantity\n"
                + "{event},{performance},reservation-1,ticket-1,account-1,2026-11-13T10:00:00Z,1\n";
    }

    private record Fixture(BulkLedgerImportWorker worker, BulkLedgerImportJob job,
                           BulkLedgerCandidatePreparationService candidates, BulkLedgerStagingWriter.Session session) { }
}
