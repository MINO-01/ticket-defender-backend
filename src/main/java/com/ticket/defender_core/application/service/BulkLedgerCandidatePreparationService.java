package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.persistence.BulkLedgerImportJobRepository;
import com.ticket.defender_core.adapter.out.persistence.BulkLedgerImportScopeRepository;
import com.ticket.defender_core.domain.BulkLedgerImportJob;
import com.ticket.defender_core.domain.BulkLedgerImportScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Creates private, immutable candidate versions only. It deliberately has no path that writes a publication head,
 * invokes analysis, or changes an existing ledger version. CHANGE_FEED is accepted only from a pre-existing
 * publication-head contract row; this issue intentionally provides no writer for that future contract.
 */
@Service
public class BulkLedgerCandidatePreparationService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final BulkLedgerImportJobRepository jobs;
    private final BulkLedgerImportScopeRepository scopes;

    public BulkLedgerCandidatePreparationService(JdbcTemplate jdbc, TransactionTemplate transactions,
                                                 BulkLedgerImportJobRepository jobs, BulkLedgerImportScopeRepository scopes) {
        this.jdbc = jdbc; this.transactions = transactions; this.jobs = jobs; this.scopes = scopes;
    }

    public void prepare(UUID importId) {
        BulkLedgerImportJob job = jobs.findById(importId).orElseThrow();
        if (!"v2".equals(job.getSchemaVersion()) || job.getStatus() != BulkLedgerImportJob.Status.READY_TO_PUBLISH) return;
        UUID candidateId = ensureCandidate(job);
        String state = jdbc.queryForObject("select status from td_bulk_ledger_candidate_imports where id=?", String.class, candidateId.toString());
        if ("READY".equals(state)) return;
        if ("REJECTED".equals(state)) return;
        try {
            List<BulkLedgerImportScope> input = scopes.findByJobIdOrderByEventIdAscPerformanceIdAsc(importId);
            for (BulkLedgerImportScope scope : input) {
                if (job.getMode() == BulkLedgerImportJob.Mode.FULL_SNAPSHOT) prepareSnapshot(candidateId, job, scope);
                else prepareChangeFeed(candidateId, job, scope);
            }
            transactions.executeWithoutResult(ignored -> jdbc.update("update td_bulk_ledger_candidate_imports set status='READY', updated_at=utc_timestamp(6) where id=? and status='PREPARING'", candidateId.toString()));
        } catch (CandidateRejected rejected) {
            reject(candidateId, rejected.code);
        } catch (RuntimeException unavailable) {
            // Candidate preparation is not a clean ledger result; retain a bounded code rather than claiming readiness.
            reject(candidateId, "CANDIDATE_PREPARATION_FAILED");
        }
    }

    private UUID ensureCandidate(BulkLedgerImportJob job) {
        List<String> existing = jdbc.query("select id from td_bulk_ledger_candidate_imports where import_id=?", (rs, n) -> rs.getString(1), job.getId().toString());
        if (!existing.isEmpty()) return UUID.fromString(existing.getFirst());
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("insert into td_bulk_ledger_candidate_imports(id,import_id,tenant_id,status,created_at,updated_at) values(?,?,?,'PREPARING',utc_timestamp(6),utc_timestamp(6))",
                    id.toString(), job.getId().toString(), job.getTenantId().toString());
            return id;
        } catch (org.springframework.dao.DataIntegrityViolationException raced) {
            return UUID.fromString(jdbc.queryForObject("select id from td_bulk_ledger_candidate_imports where import_id=?", String.class, job.getId().toString()));
        }
    }

    private void prepareSnapshot(UUID candidateId, BulkLedgerImportJob job, BulkLedgerImportScope scope) {
        Version version = transactions.execute(ignored -> {
            lockPerformance(job, scope);
            Long previous = jdbc.queryForObject("select coalesce(max(generation_no),-1) from td_bulk_ledger_versions where tenant_id=? and performance_id=?",
                    Long.class, job.getTenantId().toString(), scope.getPerformanceId().toString());
            UUID id = UUID.randomUUID();
            jdbc.update("insert into td_bulk_ledger_versions(id,candidate_import_id,import_id,tenant_id,event_id,performance_id,generation_no,revision_no,source_system,source_batch_id,source_as_of,source_cursor,source_sequence_end,status,created_at) values(?,?,?,?,?,?,?,0,?,?,?,?,?,'PREPARING',utc_timestamp(6))",
                    id.toString(), candidateId.toString(), job.getId().toString(), job.getTenantId().toString(), scope.getEventId().toString(), scope.getPerformanceId().toString(),
                    previous + 1, job.getSourceSystem(), job.getSourceBatchId(), Timestamp.valueOf(scope.getScopeAsOf()), scope.getCursorEnd(), scope.getSourceSequenceEnd());
            return new Version(id, previous + 1, 0);
        });
        List<Row> rows = rows(job.getId(), scope.getPerformanceId());
        requireSnapshotRows(rows, scope);
        insertTickets(version.id(), job.getTenantId(), rows, null);
        jdbc.update("update td_bulk_ledger_versions set status='READY' where id=? and status='PREPARING'", version.id().toString());
    }

    private void prepareChangeFeed(UUID candidateId, BulkLedgerImportJob job, BulkLedgerImportScope scope) {
        List<Row> rows = rows(job.getId(), scope.getPerformanceId());
        requireChangeRows(rows, scope);
        Version version = transactions.execute(ignored -> {
            List<Published> heads = jdbc.query("""
                    select v.id,v.generation_no,v.revision_no,v.source_cursor,v.source_sequence_end,v.source_system
                    from td_bulk_ledger_publication_heads h join td_bulk_ledger_versions v on v.id=h.version_id
                    where h.tenant_id=? and h.performance_id=? and v.status='PUBLISHED' for update
                    """, (rs, n) -> new Published(UUID.fromString(rs.getString(1)), rs.getLong(2), rs.getLong(3), rs.getString(4),
                    (Long) rs.getObject(5), rs.getString(6)), job.getTenantId().toString(), scope.getPerformanceId().toString());
            if (heads.size() != 1) throw new CandidateRejected("CHANGE_BASELINE_NOT_PUBLISHED");
            Published base = heads.getFirst();
            if (!job.getSourceSystem().equals(base.sourceSystem()) || !scope.getCursorStart().equals(base.cursor())
                    || base.sequenceEnd() == null || scope.getSourceSequenceStart() != base.sequenceEnd() + 1)
                throw new CandidateRejected("CHANGE_BASELINE_STALE");
            UUID id = UUID.randomUUID();
            jdbc.update("insert into td_bulk_ledger_versions(id,candidate_import_id,import_id,tenant_id,event_id,performance_id,generation_no,revision_no,base_version_id,source_system,source_batch_id,source_cursor,source_sequence_end,status,created_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,'PREPARING',utc_timestamp(6))",
                    id.toString(), candidateId.toString(), job.getId().toString(), job.getTenantId().toString(), scope.getEventId().toString(), scope.getPerformanceId().toString(),
                    base.generation(), base.revision() + 1, base.id().toString(), job.getSourceSystem(), job.getSourceBatchId(), scope.getCursorEnd(), scope.getSourceSequenceEnd());
            return new Version(id, base.generation(), base.revision() + 1);
        });
        assertNewEvents(job, scope, rows);
        Map<String, Row> finalRows = new LinkedHashMap<>();
        for (Row row : rows) finalRows.put(row.ticket(), row);
        insertTickets(version.id(), job.getTenantId(), new ArrayList<>(finalRows.values()), null);
        jdbc.batchUpdate("insert into td_bulk_ledger_change_events(id,version_id,tenant_id,performance_id,source_system,source_event_id,source_sequence,source_changed_at,event_digest) values(?,?,?,?,?,?,?,?,?)",
                rows, rows.size(), (statement, row) -> {
                    statement.setString(1, UUID.randomUUID().toString()); statement.setString(2, version.id().toString());
                    statement.setString(3, job.getTenantId().toString()); statement.setString(4, scope.getPerformanceId().toString());
                    statement.setString(5, job.getSourceSystem()); statement.setString(6, row.eventId()); statement.setLong(7, row.sequence()); statement.setTimestamp(8, Timestamp.valueOf(row.changedAt())); statement.setString(9, row.digest());
                });
        jdbc.update("update td_bulk_ledger_versions set status='READY' where id=? and status='PREPARING'", version.id().toString());
    }

    private void lockPerformance(BulkLedgerImportJob job, BulkLedgerImportScope scope) {
        Integer locked = jdbc.queryForObject("select count(*) from verified_performances where id=? and tenant_id=? and event_id=? for update", Integer.class,
                scope.getPerformanceId().toString(), job.getTenantId().toString(), scope.getEventId().toString());
        if (locked == null || locked != 1) throw new CandidateRejected("CANDIDATE_SCOPE_INVALID");
    }

    private List<Row> rows(UUID importId, UUID performanceId) {
        return jdbc.query("select reservation_reference_hmac,ticket_reference_hmac,account_reference_hmac,hmac_key_version,purchased_at,ticket_quantity,quantity_unit,ticket_status,source_version,source_event_id,source_changed_at,source_sequence,change_action,row_digest from td_bulk_import_staging_rows where import_id=? and performance_id=? order by coalesce(source_sequence,source_row_no)",
                (rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getTimestamp(5).toLocalDateTime(), rs.getInt(6),
                        rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getTimestamp(11) == null ? null : rs.getTimestamp(11).toLocalDateTime(), (Long) rs.getObject(12), rs.getString(13), rs.getString(14)),
                importId.toString(), performanceId.toString());
    }

    private static void requireSnapshotRows(List<Row> rows, BulkLedgerImportScope scope) {
        if (rows.size() != scope.getDeclaredRowCount()) throw new CandidateRejected("CANDIDATE_SCOPE_COUNT_MISMATCH");
        Map<String, Row> tickets = new LinkedHashMap<>();
        for (Row row : rows) { requireFacts(row); if (tickets.putIfAbsent(row.ticket(), row) != null) throw new CandidateRejected("SNAPSHOT_TICKET_DUPLICATE"); }
    }
    private static void requireChangeRows(List<Row> rows, BulkLedgerImportScope scope) {
        if (rows.size() != scope.getDeclaredRowCount()) throw new CandidateRejected("CANDIDATE_SCOPE_COUNT_MISMATCH");
        long expected = scope.getSourceSequenceStart();
        for (Row row : rows) {
            requireFacts(row);
            if (!"UPSERT".equals(row.action())) throw new CandidateRejected("CHANGE_ACTION_POLICY_PENDING");
            if (row.eventId() == null || row.changedAt() == null || row.sequence() == null || row.sequence() != expected++) throw new CandidateRejected("CHANGE_SEQUENCE_INVALID");
        }
        if (expected - 1 != scope.getSourceSequenceEnd()) throw new CandidateRejected("CHANGE_SEQUENCE_INVALID");
    }
    private static void requireFacts(Row row) {
        if (row.unit() == null || row.status() == null || row.sourceVersion() == null || row.purchasedAt() == null
                || !List.of("TICKET", "ORDER_LINE").contains(row.unit()) || !List.of("ACTIVE", "CANCELLED", "REFUNDED").contains(row.status())
                || row.quantity() < 1 || ("TICKET".equals(row.unit()) && row.quantity() != 1)) throw new CandidateRejected("LEDGER_FACTS_INCOMPLETE");
    }
    private void assertNewEvents(BulkLedgerImportJob job, BulkLedgerImportScope scope, List<Row> rows) {
        for (Row row : rows) {
            List<String> digests = jdbc.query("select event_digest from td_bulk_ledger_change_events where tenant_id=? and performance_id=? and source_system=? and source_event_id=?",
                    (rs, n) -> rs.getString(1), job.getTenantId().toString(), scope.getPerformanceId().toString(), job.getSourceSystem(), row.eventId());
            if (!digests.isEmpty()) throw new CandidateRejected(digests.getFirst().equals(row.digest()) ? "CHANGE_EVENT_DUPLICATE" : "CHANGE_EVENT_CONFLICT");
        }
    }
    private void insertTickets(UUID versionId, UUID tenantId, List<Row> rows, String ignored) {
        jdbc.batchUpdate("insert into td_bulk_ledger_ticket_versions(id,version_id,tenant_id,ticket_reference_hmac,reservation_reference_hmac,account_reference_hmac,hmac_key_version,purchased_at,ticket_quantity,quantity_unit,ticket_status,source_version,source_event_id) values(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                rows, 1000, (statement, row) -> {
                    statement.setString(1, UUID.randomUUID().toString()); statement.setString(2, versionId.toString()); statement.setString(3, tenantId.toString());
                    statement.setString(4, row.ticket()); statement.setString(5, row.reservation()); statement.setString(6, row.account()); statement.setString(7, row.keyVersion());
                    statement.setTimestamp(8, Timestamp.valueOf(row.purchasedAt())); statement.setInt(9, row.quantity()); statement.setString(10, row.unit()); statement.setString(11, row.status()); statement.setString(12, row.sourceVersion()); statement.setString(13, row.eventId());
                });
    }
    private void reject(UUID candidateId, String code) {
        transactions.executeWithoutResult(ignored -> {
            jdbc.update("update td_bulk_ledger_candidate_imports set status='REJECTED', failure_code=?, updated_at=utc_timestamp(6) where id=? and status='PREPARING'", code, candidateId.toString());
            jdbc.update("update td_bulk_ledger_versions set status='REJECTED' where candidate_import_id=? and status in ('PREPARING','READY')", candidateId.toString());
        });
    }
    private record Version(UUID id, long generation, long revision) { }
    private record Published(UUID id, long generation, long revision, String cursor, Long sequenceEnd, String sourceSystem) { }
    private record Row(String reservation, String ticket, String account, String keyVersion, LocalDateTime purchasedAt, int quantity,
                       String unit, String status, String sourceVersion, String eventId, LocalDateTime changedAt, Long sequence, String action, String digest) { }
    private static final class CandidateRejected extends RuntimeException { private CandidateRejected(String code) { this.code = code; } private final String code; }
}
