package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.persistence.BulkLedgerImportJobRepository;
import com.ticket.defender_core.adapter.out.persistence.BulkLedgerImportErrorRepository;
import com.ticket.defender_core.adapter.out.persistence.BulkLedgerImportScopeRepository;
import com.ticket.defender_core.domain.BulkLedgerImportJob;
import com.ticket.defender_core.domain.BulkLedgerImportError;
import com.ticket.defender_core.domain.BulkLedgerImportScope;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.EnumSet;

/**
 * Single-import worker. Its DB state transitions use short transactions; object reads and CSV parsing are outside them.
 * It writes only private staging and never publishes a ledger head or triggers Track 1/2 analysis.
 */
@Service
public class BulkLedgerImportWorker {
    private final BulkLedgerImportJobRepository jobs;
    private final BulkLedgerImportScopeRepository scopes;
    private final BulkLedgerImportErrorRepository errors;
    private final SyntheticReservationImportGate gate;
    private final BulkLedgerUploadStorage storage;
    private final BulkLedgerCsvStagingParser parser = new BulkLedgerCsvStagingParser();
    private final BulkLedgerStagingWriter staging;
    private final TransactionTemplate transactions;
    @Value("${ticket-defender.bulk-ledger-upload.worker-enabled:false}") private boolean enabled;

    public BulkLedgerImportWorker(BulkLedgerImportJobRepository jobs, BulkLedgerImportScopeRepository scopes, BulkLedgerImportErrorRepository errors,
            SyntheticReservationImportGate gate, BulkLedgerUploadStorage storage, BulkLedgerStagingWriter staging,
            TransactionTemplate transactions) {
        this.jobs = jobs; this.scopes = scopes; this.errors = errors; this.gate = gate; this.storage = storage; this.staging = staging; this.transactions = transactions;
    }

    public void process(UUID importId) {
        Lease lease = transactions.execute(status -> acquire(importId));
        if (lease == null) return;
        try {
            // Re-read exact object version: a current-object replacement cannot alter the staged source.
            storage.verify(objectKey(importId), lease.objectVersion(), lease.maximumBytes());
            List<BulkLedgerImportScope> jobScopes = scopes.findByJobIdOrderByEventIdAscPerformanceIdAsc(importId);
            gate.requireSyntheticScope(lease.tenantId(), LocalDateTime.now(ZoneOffset.UTC));
            var reservation = gate.referenceHasher(); // intentionally preserves Track 2 reservation contract
            var ticket = gate.referenceHasher("BL02_TICKET_REFERENCE");
            var account = gate.referenceHasher("BL02_ACCOUNT_REFERENCE");
            BulkLedgerStagingWriter.Session stagingSession = staging.open(importId, lease.tenantId(), lease.fence());
            BulkLedgerCsvStagingParser.Result result;
            try (InputStream source = storage.openVersion(objectKey(importId), lease.objectVersion())) {
                result = parser.parse(source, lease.mode(), lease.tenantId(), jobScopes, reservation, ticket, account, stagingSession::accept);
            }
            stagingSession.finish();
            if (result.rows() != lease.declaredRows()) throw new BulkLedgerCsvValidationException(0, "BULK_CSV_DECLARED_ROW_COUNT_MISMATCH");
            transactions.executeWithoutResult(status -> ready(importId, lease.fence(), result.rows()));
        } catch (BulkLedgerCsvValidationException rejected) {
            transactions.executeWithoutResult(status -> rejected(importId, lease.fence(), rejected));
        } catch (BulkLedgerUploadStorage.ObjectChanged | BulkLedgerUploadStorage.InvalidObject changed) {
            transactions.executeWithoutResult(status -> rejected(importId, lease.fence(),
                    new BulkLedgerCsvValidationException(0, "BULK_UPLOAD_OBJECT_VERSION_CHANGED")));
        } catch (RuntimeException | java.io.IOException unavailable) {
            transactions.executeWithoutResult(status -> retryable(importId, lease.fence()));
        }
    }

    /** Disabled by default; explicit synthetic environments can resume committed uploads after a restart. */
    @Scheduled(fixedDelayString = "${ticket-defender.bulk-ledger-upload.worker-delay:PT30S}")
    public void resumePending() {
        if (!enabled) return;
        jobs.findTop10ByStatusInOrderByUpdatedAtAsc(List.copyOf(EnumSet.of(BulkLedgerImportJob.Status.UPLOADED,
                BulkLedgerImportJob.Status.FAILED_RETRYABLE, BulkLedgerImportJob.Status.VALIDATING,
                BulkLedgerImportJob.Status.STAGING))).forEach(job -> process(job.getId()));
    }

    private Lease acquire(UUID importId) {
        BulkLedgerImportJob job = jobs.findById(importId).orElse(null); if (job == null) return null;
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        try {
            job.startValidation("bl02-" + UUID.randomUUID(), now.plusMinutes(5), now);
            job.beginStaging(job.getFenceToken(), now);
        } catch (IllegalStateException notRunnable) { return null; }
        jobs.saveAndFlush(job);
        return new Lease(job.getTenantId(), job.getMode(), job.getUploadObjectVersion(), job.getDeclaredFileBytes(), job.getDeclaredRowCount(), job.getFenceToken());
    }
    private void ready(UUID importId, long fence, long rows) {
        BulkLedgerImportJob job = jobs.findById(importId).orElseThrow(); job.readyToPublish(fence, rows, rows, 0, LocalDateTime.now(ZoneOffset.UTC)); jobs.saveAndFlush(job);
    }
    private void rejected(UUID importId, long fence, BulkLedgerCsvValidationException error) {
        BulkLedgerImportJob job = jobs.findById(importId).orElseThrow();
        long processed = Math.max(0, error.rowNumber() - 2); long rejected = processed > 0 ? 1 : 0;
        job.reject(fence, processed, rejected, LocalDateTime.now(ZoneOffset.UTC)); jobs.saveAndFlush(job);
        // Error rows intentionally contain only a row number and bounded code, never a source value.
        errors.save(new BulkLedgerImportError(importId, Math.max(0, error.rowNumber()), error.code(), LocalDateTime.now(ZoneOffset.UTC)));
    }
    private void retryable(UUID importId, long fence) {
        BulkLedgerImportJob job = jobs.findById(importId).orElseThrow(); job.retryableFailure(fence, LocalDateTime.now(ZoneOffset.UTC)); jobs.saveAndFlush(job);
    }
    private static String objectKey(UUID id) { return "bulk-ledger-private/" + id + "/upload.csv"; }
    private record Lease(UUID tenantId, BulkLedgerImportJob.Mode mode, String objectVersion, long maximumBytes, long declaredRows, long fence) { }
}
