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
import java.util.Map;

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
    private final BulkLedgerCandidatePreparationService candidates;
    private final TransactionTemplate transactions;
    private final BulkLedgerRawIntakePolicy rawIntake;
    private final BulkLedgerRawSignalHasherFactory signalHashers;
    private final BulkLedgerUploadDeletionService deletions;
    @Value("${ticket-defender.bulk-ledger-upload.worker-enabled:false}") private boolean enabled;

    public BulkLedgerImportWorker(BulkLedgerImportJobRepository jobs, BulkLedgerImportScopeRepository scopes, BulkLedgerImportErrorRepository errors,
            SyntheticReservationImportGate gate, BulkLedgerUploadStorage storage, BulkLedgerStagingWriter staging,
            BulkLedgerCandidatePreparationService candidates, TransactionTemplate transactions) {
        this(jobs, scopes, errors, gate, storage, staging, candidates, transactions, null, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public BulkLedgerImportWorker(BulkLedgerImportJobRepository jobs, BulkLedgerImportScopeRepository scopes, BulkLedgerImportErrorRepository errors,
            SyntheticReservationImportGate gate, BulkLedgerUploadStorage storage, BulkLedgerStagingWriter staging,
            BulkLedgerCandidatePreparationService candidates, TransactionTemplate transactions,
            BulkLedgerRawIntakePolicy rawIntake, BulkLedgerRawSignalHasherFactory signalHashers, BulkLedgerUploadDeletionService deletions) {
        this.jobs = jobs; this.scopes = scopes; this.errors = errors; this.gate = gate; this.storage = storage; this.staging = staging; this.candidates = candidates; this.transactions = transactions;
        this.rawIntake = rawIntake; this.signalHashers = signalHashers; this.deletions = deletions;
    }

    public void process(UUID importId) {
        Lease lease = transactions.execute(status -> acquire(importId));
        if (lease == null) return;
        boolean stagingReady = false;
        boolean stagingFinal = false;
        boolean v3 = "v3".equals(lease.schemaVersion());
        if (deletions != null && deletions.retentionExpired(importId)) {
            // 원본이 이미 지워져 재시도해도 성공할 수 없으므로 기존 거절 전이로 확정 실패를 남깁니다.
            transactions.executeWithoutResult(status -> rejected(importId, lease.fence(),
                    new BulkLedgerCsvValidationException(0, BulkLedgerUploadDeletionService.UPLOAD_OBJECT_EXPIRED)));
            return;
        }
        if (v3 && (rawIntake == null || signalHashers == null || !rawIntake.v3Accepted())) {
            // v3 원본 수신이 닫혀 있으면 원본을 읽지 않고 확정 거절한 뒤 업로드 객체를 지웁니다.
            transactions.executeWithoutResult(status -> rejected(importId, lease.fence(),
                    new BulkLedgerCsvValidationException(0, BulkLedgerRawIntakePolicy.DISABLED)));
            deleteUpload(importId, lease.tenantId(), BulkLedgerUploadDeletionService.Reason.STAGING_REJECTED);
            return;
        }
        try {
            // Re-read exact object version: a current-object replacement cannot alter the staged source.
            storage.verify(objectKey(importId), lease.objectVersion(), lease.maximumBytes());
            List<BulkLedgerImportScope> jobScopes = scopes.findByJobIdOrderByEventIdAscPerformanceIdAsc(importId);
            transactions.executeWithoutResult(status -> gate.requireSyntheticScope(lease.tenantId(), LocalDateTime.now(ZoneOffset.UTC)));
            var reservation = gate.referenceHasher(); // intentionally preserves Track 2 reservation contract
            var ticket = gate.referenceHasher("BL02_TICKET_REFERENCE");
            var account = gate.referenceHasher("BL02_ACCOUNT_REFERENCE");
            BulkLedgerRawSignalHashers rawSignals = v3 ? signalHashers.hashers() : null;
            BulkLedgerStagingWriter.Session stagingSession = staging.open(importId, lease.tenantId(), lease.fence());
            BulkLedgerCsvStagingParser.Result result;
            try (InputStream source = storage.openVersion(objectKey(importId), lease.objectVersion())) {
                result = parser.parse(source, lease.schemaVersion(), lease.mode(), lease.tenantId(), jobScopes, reservation, ticket, account,
                        rawSignals, stagingSession::accept);
            }
            stagingSession.finish();
            if (result.rows() != lease.declaredRows()) throw new BulkLedgerCsvValidationException(0, "BULK_CSV_DECLARED_ROW_COUNT_MISMATCH");
            if (staging.countForImport(importId) != lease.declaredRows())
                throw new BulkLedgerCsvValidationException(0, "BULK_STAGING_ROW_COUNT_MISMATCH");
            Map<String, Long> stagedScopes = staging.scopeCountsForImport(importId);
            for (BulkLedgerImportScope scope : jobScopes) {
                String key = scope.getEventId() + ":" + scope.getPerformanceId();
                if (stagedScopes.getOrDefault(key, 0L) != scope.getDeclaredRowCount())
                    throw new BulkLedgerCsvValidationException(0, "BULK_STAGING_SCOPE_ROW_COUNT_MISMATCH");
            }
            transactions.executeWithoutResult(status -> ready(importId, lease.fence(), result.rows()));
            stagingReady = true;
            stagingFinal = true;
        } catch (BulkLedgerCsvValidationException rejected) {
            transactions.executeWithoutResult(status -> rejected(importId, lease.fence(), rejected));
            stagingFinal = true;
        } catch (BulkLedgerStagingConflictException conflict) {
            transactions.executeWithoutResult(status -> rejected(importId, lease.fence(),
                    new BulkLedgerCsvValidationException(conflict.rowNumber(), "BULK_STAGING_ROW_CONFLICT")));
            stagingFinal = true;
        } catch (BulkLedgerUploadStorage.ObjectChanged | BulkLedgerUploadStorage.InvalidObject changed) {
            transactions.executeWithoutResult(status -> rejected(importId, lease.fence(),
                    new BulkLedgerCsvValidationException(0, "BULK_UPLOAD_OBJECT_VERSION_CHANGED")));
            stagingFinal = true;
        } catch (RuntimeException | java.io.IOException unavailable) {
            // 재시도 가능한 실패에서는 원본이 다시 필요하므로 지우지 않습니다.
            transactions.executeWithoutResult(status -> retryable(importId, lease.fence()));
        }
        if (stagingFinal) deleteUpload(importId, lease.tenantId(), stagingReady
                ? BulkLedgerUploadDeletionService.Reason.STAGING_COMPLETED : BulkLedgerUploadDeletionService.Reason.STAGING_REJECTED);

        if (!stagingReady) return;
        // readyToPublish releases the staging fence. Candidate preparation owns a separate lease, so a
        // candidate database failure must not try to transition the already-complete staging job again.
        try {
            candidates.prepare(importId);
        } catch (RuntimeException ignored) {
            // The scheduler resumes READY_TO_PUBLISH v2 jobs through candidate preparation only.
        }
    }

    /** Disabled by default; explicit synthetic environments can resume committed uploads after a restart. */
    @Scheduled(fixedDelayString = "${ticket-defender.bulk-ledger-upload.worker-delay:PT30S}")
    public void resumePending() {
        if (!enabled) return;
        jobs.findTop10ByStatusInOrderByUpdatedAtAsc(List.copyOf(EnumSet.of(BulkLedgerImportJob.Status.UPLOADED,
                BulkLedgerImportJob.Status.FAILED_RETRYABLE, BulkLedgerImportJob.Status.VALIDATING,
                BulkLedgerImportJob.Status.STAGING))).forEach(job -> process(job.getId()));
        // Staging already released its fence at READY_TO_PUBLISH. Candidate preparation has its own DB lease and never reuses it.
        jobs.findTop10CandidatePreparationPending()
                .forEach(job -> candidates.prepare(job.getId()));
    }

    private Lease acquire(UUID importId) {
        BulkLedgerImportJob job = jobs.findById(importId).orElse(null); if (job == null) return null;
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        try {
            job.startValidation("bl02-" + UUID.randomUUID(), now.plusMinutes(5), now);
            job.beginStaging(job.getFenceToken(), now);
        } catch (IllegalStateException notRunnable) { return null; }
        jobs.saveAndFlush(job);
        return new Lease(job.getTenantId(), job.getSchemaVersion(), job.getMode(), job.getUploadObjectVersion(), job.getDeclaredFileBytes(), job.getDeclaredRowCount(), job.getFenceToken());
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
    /** 스테이징 성공 또는 확정 거절 직후 원본 객체를 지웁니다. 실패는 작업 상태를 바꾸지 않고 별도 기록만 남깁니다. */
    private void deleteUpload(UUID importId, UUID tenantId, BulkLedgerUploadDeletionService.Reason reason) {
        if (deletions != null) deletions.deleteUpload(importId, tenantId, reason);
    }
    private static String objectKey(UUID id) { return "bulk-ledger-private/" + id + "/upload.csv"; }
    private record Lease(UUID tenantId, String schemaVersion, BulkLedgerImportJob.Mode mode, String objectVersion, long maximumBytes, long declaredRows, long fence) { }
}
