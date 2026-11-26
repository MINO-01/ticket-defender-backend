package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.persistence.BulkLedgerImportJobRepository;
import com.ticket.defender_core.adapter.out.persistence.BulkLedgerImportScopeRepository;
import com.ticket.defender_core.domain.BulkLedgerImportJob;
import com.ticket.defender_core.domain.BulkLedgerImportScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Prepares private candidate versions. Publication heads and analysis are deliberately outside this service. */
@Service
public class BulkLedgerCandidatePreparationService {
    private static final int PAGE_SIZE = 1_000;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final BulkLedgerImportJobRepository jobs;
    private final BulkLedgerImportScopeRepository scopes;

    public BulkLedgerCandidatePreparationService(JdbcTemplate jdbc, TransactionTemplate transactions,
                                                 BulkLedgerImportJobRepository jobs, BulkLedgerImportScopeRepository scopes) {
        this.jdbc = jdbc; this.transactions = transactions; this.jobs = jobs; this.scopes = scopes;
    }

    /** A short DB lease makes a READY_TO_PUBLISH candidate safely resumable without re-running upload or staging. */
    public void prepare(UUID importId) {
        BulkLedgerImportJob job = jobs.findById(importId).orElseThrow();
        // v3는 v2 원장 사실을 그대로 포함하므로 같은 후보 준비 경로를 씁니다.
        if (!Set.of("v2", "v3").contains(job.getSchemaVersion()) || job.getStatus() != BulkLedgerImportJob.Status.READY_TO_PUBLISH) return;
        UUID candidateId = ensureCandidate(job);
        Lease lease = claim(candidateId);
        if (lease == null) return;
        try {
            List<BulkLedgerImportScope> input = scopes.findByJobIdOrderByEventIdAscPerformanceIdAsc(importId);
            for (BulkLedgerImportScope scope : input) {
                if (job.getMode() == BulkLedgerImportJob.Mode.FULL_SNAPSHOT) prepareSnapshot(lease, job, scope);
                else prepareChangeFeed(lease, job, scope);
            }
            markReady(lease, input.size());
        } catch (CandidateRejected rejected) {
            try { reject(lease, rejected.code); } catch (CandidateLeaseLost ignored) { }
        } catch (CandidateLeaseLost lost) {
            // A newer worker owns the candidate fence; the stale worker must not change its outcome.
        } catch (RuntimeException unavailable) {
            // Database availability/races are not input facts. Leave the candidate resumable and record a bounded retry reason.
            retryable(lease, "CANDIDATE_PREPARATION_RETRYABLE");
        }
    }

    private UUID ensureCandidate(BulkLedgerImportJob job) {
        List<String> existing = jdbc.query("select id from td_bulk_ledger_candidate_imports where import_id=?", (rs, n) -> rs.getString(1), job.getId().toString());
        if (!existing.isEmpty()) return UUID.fromString(existing.getFirst());
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("insert into td_bulk_ledger_candidate_imports(id,import_id,tenant_id,source_object_version,source_object_sha256,status,preparation_attempt_count,created_at,updated_at) values(?,?,?,?,?,'PREPARING',0,utc_timestamp(6),utc_timestamp(6))",
                    id.toString(), job.getId().toString(), job.getTenantId().toString(), job.getUploadObjectVersion(), job.getDeclaredObjectSha256());
            return id;
        } catch (org.springframework.dao.DataIntegrityViolationException raced) {
            return UUID.fromString(jdbc.queryForObject("select id from td_bulk_ledger_candidate_imports where import_id=?", String.class, job.getId().toString()));
        }
    }

    private Lease claim(UUID candidateId) {
        String owner = "bl03-" + UUID.randomUUID();
        int updated = jdbc.update("""
                update td_bulk_ledger_candidate_imports
                set preparation_owner=?, preparation_lease_until=date_add(utc_timestamp(6), interval 5 minute),
                    preparation_attempt_count=preparation_attempt_count+1, preparation_fence_token=preparation_fence_token+1,
                    last_retry_code=null, updated_at=utc_timestamp(6)
                where id=? and status='PREPARING'
                  and (preparation_lease_until is null or preparation_lease_until < utc_timestamp(6))
                """, owner, candidateId.toString());
        if (updated != 1) return null;
        Long fence = jdbc.queryForObject("select preparation_fence_token from td_bulk_ledger_candidate_imports where id=? and preparation_owner=?", Long.class,
                candidateId.toString(), owner);
        return new Lease(candidateId, fence);
    }

    private void prepareSnapshot(Lease lease, BulkLedgerImportJob job, BulkLedgerImportScope scope) {
        if (scope.getScopeAsOf() == null) throw new CandidateRejected("CANDIDATE_SCOPE_INVALID");
        Version version = ensureSnapshotVersion(lease, job, scope);
        if ("READY".equals(version.status())) return;
        validateSnapshotRows(job, scope);
        long afterRow = 0;
        while (true) {
            List<Row> page = snapshotPage(job, scope, afterRow);
            if (page.isEmpty()) break;
            writeTicketPage(lease, version.id(), job.getTenantId(), page);
            afterRow = page.getLast().rowNo();
        }
        finishVersion(lease, version.id());
    }

    private Version ensureSnapshotVersion(Lease lease, BulkLedgerImportJob job, BulkLedgerImportScope scope) {
        return transactions.execute(ignored -> {
            requireLease(lease);
            Version existing = candidateVersion(lease.candidateId(), scope.getPerformanceId());
            if (existing != null) return existing;
            lockPerformance(job, scope);
            UUID expectedHead = publicationHead(job.getTenantId(), scope.getPerformanceId());
            Long previous = jdbc.queryForObject("select coalesce(max(generation_no),-1) from td_bulk_ledger_versions where tenant_id=? and performance_id=?",
                    Long.class, job.getTenantId().toString(), scope.getPerformanceId().toString());
            UUID id = UUID.randomUUID();
            jdbc.update("insert into td_bulk_ledger_versions(id,candidate_import_id,import_id,tenant_id,event_id,performance_id,generation_no,revision_no,expected_head_known,expected_head_version_id,source_system,source_batch_id,source_as_of,source_cursor,source_sequence_end,status,created_at) values(?,?,?,?,?,?,?,0,1,?,?,?,?,?,?,'PREPARING',utc_timestamp(6))",
                    id.toString(), lease.candidateId().toString(), job.getId().toString(), job.getTenantId().toString(), scope.getEventId().toString(), scope.getPerformanceId().toString(),
                    previous + 1, expectedHead == null ? null : expectedHead.toString(), job.getSourceSystem(), job.getSourceBatchId(), Timestamp.valueOf(scope.getScopeAsOf()), scope.getCursorEnd(), scope.getSourceSequenceEnd());
            return new Version(id, "PREPARING");
        });
    }

    private void validateSnapshotRows(BulkLedgerImportJob job, BulkLedgerImportScope scope) {
        long count = scopeRowCount(job, scope);
        if (count != scope.getDeclaredRowCount()) throw new CandidateRejected("CANDIDATE_SCOPE_COUNT_MISMATCH");
        Long invalid = jdbc.queryForObject("""
                select count(*) from td_bulk_import_staging_rows
                where import_id=? and tenant_id=? and event_id=? and performance_id=?
                  and (quantity_unit is null or ticket_status is null or source_version is null or purchased_at is null
                       or quantity_unit not in ('TICKET','ORDER_LINE') or ticket_status not in ('ACTIVE','CANCELLED','REFUNDED')
                       or ticket_quantity < 1 or (quantity_unit='TICKET' and ticket_quantity <> 1))
                """, Long.class, job.getId().toString(), job.getTenantId().toString(), scope.getEventId().toString(), scope.getPerformanceId().toString());
        if (invalid != null && invalid > 0) throw new CandidateRejected("LEDGER_FACTS_INCOMPLETE");
        Long duplicate = jdbc.queryForObject("""
                select count(*) from (
                    select ticket_reference_hmac from td_bulk_import_staging_rows
                    where import_id=? and tenant_id=? and event_id=? and performance_id=?
                    group by ticket_reference_hmac having count(*) > 1
                ) duplicated
                """, Long.class, job.getId().toString(), job.getTenantId().toString(), scope.getEventId().toString(), scope.getPerformanceId().toString());
        if (duplicate != null && duplicate > 0) throw new CandidateRejected("SNAPSHOT_TICKET_DUPLICATE");
    }

    private void prepareChangeFeed(Lease lease, BulkLedgerImportJob job, BulkLedgerImportScope scope) {
        if (scope.getCursorStart() == null || scope.getCursorEnd() == null || scope.getSourceSequenceStart() == null || scope.getSourceSequenceEnd() == null)
            throw new CandidateRejected("CHANGE_SEQUENCE_INVALID");
        if (scopeRowCount(job, scope) != scope.getDeclaredRowCount()) throw new CandidateRejected("CANDIDATE_SCOPE_COUNT_MISMATCH");
        Version version = ensureChangeVersion(lease, job, scope);
        if ("READY".equals(version.status())) return;
        long expected = scope.getSourceSequenceStart();
        long afterSequence = -1;
        long afterRow = -1;
        while (true) {
            List<Row> page = changePage(job, scope, afterSequence, afterRow);
            if (page.isEmpty()) break;
            for (Row row : page) {
                requireChangeFacts(row, expected);
                expected++;
            }
            writeChangePage(lease, version.id(), job, scope, page);
            Row last = page.getLast(); afterSequence = last.sequence(); afterRow = last.rowNo();
        }
        if (expected - 1 != scope.getSourceSequenceEnd()) throw new CandidateRejected("CHANGE_SEQUENCE_INVALID");
        finishVersion(lease, version.id());
    }

    private Version ensureChangeVersion(Lease lease, BulkLedgerImportJob job, BulkLedgerImportScope scope) {
        return transactions.execute(ignored -> {
            requireLease(lease);
            Version existing = candidateVersion(lease.candidateId(), scope.getPerformanceId());
            if (existing != null) return existing;
            lockPerformance(job, scope);
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
            Long concurrent = jdbc.queryForObject("""
                    select count(*) from td_bulk_ledger_versions
                    where base_version_id=? and status <> 'REJECTED'
                    """, Long.class, base.id().toString());
            if (concurrent != null && concurrent > 0) throw new CandidateRejected("CHANGE_BASELINE_STALE");
            Long nextRevision = jdbc.queryForObject("select coalesce(max(revision_no),?) + 1 from td_bulk_ledger_versions where tenant_id=? and performance_id=? and generation_no=?",
                    Long.class, base.revision(), job.getTenantId().toString(), scope.getPerformanceId().toString(), base.generation());
            UUID id = UUID.randomUUID();
            jdbc.update("insert into td_bulk_ledger_versions(id,candidate_import_id,import_id,tenant_id,event_id,performance_id,generation_no,revision_no,base_version_id,expected_head_known,expected_head_version_id,source_system,source_batch_id,source_cursor,source_sequence_end,status,created_at) values(?,?,?,?,?,?,?,?,?,1,?,?,?,?,?, 'PREPARING',utc_timestamp(6))",
                    id.toString(), lease.candidateId().toString(), job.getId().toString(), job.getTenantId().toString(), scope.getEventId().toString(), scope.getPerformanceId().toString(),
                    base.generation(), nextRevision, base.id().toString(), base.id().toString(), job.getSourceSystem(), job.getSourceBatchId(), scope.getCursorEnd(), scope.getSourceSequenceEnd());
            return new Version(id, "PREPARING");
        });
    }

    private void writeChangePage(Lease lease, UUID versionId, BulkLedgerImportJob job, BulkLedgerImportScope scope, List<Row> page) {
        transactions.executeWithoutResult(ignored -> {
            requireLease(lease);
            writeChangeEvents(lease.candidateId(), versionId, job, scope, page);
            writeTickets(versionId, job.getTenantId(), page);
        });
    }

    private void writeChangeEvents(UUID candidateId, UUID versionId, BulkLedgerImportJob job, BulkLedgerImportScope scope, List<Row> page) {
        List<Row> newEvents = new ArrayList<>();
        for (Row row : page) if (eventIsNew(candidateId, job, scope, row)) newEvents.add(row);
        if (newEvents.isEmpty()) return;
        jdbc.batchUpdate("insert into td_bulk_ledger_change_events(id,version_id,tenant_id,performance_id,source_system,source_event_id,source_sequence,source_changed_at,event_digest) values(?,?,?,?,?,?,?,?,?)",
                newEvents, PAGE_SIZE, (statement, row) -> {
                    statement.setString(1, UUID.randomUUID().toString()); statement.setString(2, versionId.toString());
                    statement.setString(3, job.getTenantId().toString()); statement.setString(4, scope.getPerformanceId().toString());
                    statement.setString(5, job.getSourceSystem()); statement.setString(6, row.eventId()); statement.setLong(7, row.sequence());
                    statement.setTimestamp(8, Timestamp.valueOf(row.changedAt())); statement.setString(9, row.digest());
                });
    }

    private boolean eventIsNew(UUID candidateId, BulkLedgerImportJob job, BulkLedgerImportScope scope, Row row) {
        List<Event> existing = jdbc.query("""
                select ce.event_digest,v.candidate_import_id,v.status from td_bulk_ledger_change_events ce
                join td_bulk_ledger_versions v on v.id=ce.version_id
                where ce.tenant_id=? and ce.performance_id=? and ce.source_system=? and ce.source_event_id=?
                """, (rs, n) -> new Event(rs.getString(1), UUID.fromString(rs.getString(2)), rs.getString(3)),
                job.getTenantId().toString(), scope.getPerformanceId().toString(), job.getSourceSystem(), row.eventId());
        if (existing.isEmpty()) return true;
        Event found = existing.getFirst();
        if (candidateId.equals(found.candidateId())) {
            if (!found.digest().equals(row.digest())) throw new CandidateRejected("CHANGE_EVENT_CONFLICT");
            return false;
        }
        if ("REJECTED".equals(found.versionStatus())) return true; // defensive compatibility for rows predating BL-04 cleanup.
        throw new CandidateRejected(found.digest().equals(row.digest()) ? "CHANGE_EVENT_DUPLICATE" : "CHANGE_EVENT_CONFLICT");
    }

    private void writeTickets(UUID versionId, UUID tenantId, List<Row> rows) {
        jdbc.batchUpdate("""
                insert into td_bulk_ledger_ticket_versions(id,version_id,tenant_id,ticket_reference_hmac,reservation_reference_hmac,account_reference_hmac,hmac_key_version,purchased_at,ticket_quantity,quantity_unit,ticket_status,source_version,source_event_id)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?)
                on duplicate key update reservation_reference_hmac=values(reservation_reference_hmac), account_reference_hmac=values(account_reference_hmac),
                    hmac_key_version=values(hmac_key_version), purchased_at=values(purchased_at), ticket_quantity=values(ticket_quantity),
                    quantity_unit=values(quantity_unit), ticket_status=values(ticket_status), source_version=values(source_version), source_event_id=values(source_event_id)
                """, rows, PAGE_SIZE, (statement, row) -> {
                    statement.setString(1, UUID.randomUUID().toString()); statement.setString(2, versionId.toString()); statement.setString(3, tenantId.toString());
                    statement.setString(4, row.ticket()); statement.setString(5, row.reservation()); statement.setString(6, row.account()); statement.setString(7, row.keyVersion());
                    statement.setTimestamp(8, Timestamp.valueOf(row.purchasedAt())); statement.setInt(9, row.quantity()); statement.setString(10, row.unit());
                    statement.setString(11, row.status()); statement.setString(12, row.sourceVersion()); statement.setString(13, row.eventId());
                });
    }

    private void writeTicketPage(Lease lease, UUID versionId, UUID tenantId, List<Row> rows) {
        transactions.executeWithoutResult(ignored -> {
            requireLease(lease);
            writeTickets(versionId, tenantId, rows);
        });
    }

    private List<Row> snapshotPage(BulkLedgerImportJob job, BulkLedgerImportScope scope, long afterRow) {
        return jdbc.query("""
                select source_row_no,reservation_reference_hmac,ticket_reference_hmac,account_reference_hmac,hmac_key_version,purchased_at,ticket_quantity,quantity_unit,ticket_status,source_version,source_event_id,source_changed_at,source_sequence,change_action,row_digest
                from td_bulk_import_staging_rows
                where import_id=? and tenant_id=? and event_id=? and performance_id=? and source_row_no > ?
                order by source_row_no limit ?
                """, (rs, n) -> row(rs), job.getId().toString(), job.getTenantId().toString(), scope.getEventId().toString(), scope.getPerformanceId().toString(), afterRow, PAGE_SIZE);
    }

    private List<Row> changePage(BulkLedgerImportJob job, BulkLedgerImportScope scope, long afterSequence, long afterRow) {
        return jdbc.query("""
                select source_row_no,reservation_reference_hmac,ticket_reference_hmac,account_reference_hmac,hmac_key_version,purchased_at,ticket_quantity,quantity_unit,ticket_status,source_version,source_event_id,source_changed_at,source_sequence,change_action,row_digest
                from td_bulk_import_staging_rows
                where import_id=? and tenant_id=? and event_id=? and performance_id=?
                  and (source_sequence > ? or (source_sequence = ? and source_row_no > ?))
                order by source_sequence,source_row_no limit ?
                """, (rs, n) -> row(rs), job.getId().toString(), job.getTenantId().toString(), scope.getEventId().toString(), scope.getPerformanceId().toString(), afterSequence, afterSequence, afterRow, PAGE_SIZE);
    }

    private static Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp changed = rs.getTimestamp(12);
        Long sequence = (Long) rs.getObject(13);
        return new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getTimestamp(6).toLocalDateTime(),
                rs.getInt(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), changed == null ? null : changed.toLocalDateTime(), sequence, rs.getString(14), rs.getString(15));
    }

    private long scopeRowCount(BulkLedgerImportJob job, BulkLedgerImportScope scope) {
        Long count = jdbc.queryForObject("select count(*) from td_bulk_import_staging_rows where import_id=? and tenant_id=? and event_id=? and performance_id=?", Long.class,
                job.getId().toString(), job.getTenantId().toString(), scope.getEventId().toString(), scope.getPerformanceId().toString());
        return count == null ? 0 : count;
    }

    private static void requireChangeFacts(Row row, long expected) {
        requireFacts(row);
        if (!"UPSERT".equals(row.action())) throw new CandidateRejected("CHANGE_ACTION_POLICY_PENDING");
        if (row.eventId() == null || row.changedAt() == null || row.sequence() == null || row.sequence() != expected)
            throw new CandidateRejected("CHANGE_SEQUENCE_INVALID");
    }

    private static void requireFacts(Row row) {
        if (row.unit() == null || row.status() == null || row.sourceVersion() == null || row.purchasedAt() == null
                || !List.of("TICKET", "ORDER_LINE").contains(row.unit()) || !List.of("ACTIVE", "CANCELLED", "REFUNDED").contains(row.status())
                || row.quantity() < 1 || ("TICKET".equals(row.unit()) && row.quantity() != 1)) throw new CandidateRejected("LEDGER_FACTS_INCOMPLETE");
    }

    private Version candidateVersion(UUID candidateId, UUID performanceId) {
        List<Version> found = jdbc.query("select id,status from td_bulk_ledger_versions where candidate_import_id=? and performance_id=?", (rs, n) ->
                new Version(UUID.fromString(rs.getString(1)), rs.getString(2)), candidateId.toString(), performanceId.toString());
        return found.isEmpty() ? null : found.getFirst();
    }

    private void lockPerformance(BulkLedgerImportJob job, BulkLedgerImportScope scope) {
        List<Integer> locked = jdbc.query("select 1 from verified_performances where id=? and tenant_id=? and event_id=? for update", (rs, n) -> rs.getInt(1),
                scope.getPerformanceId().toString(), job.getTenantId().toString(), scope.getEventId().toString());
        if (locked.size() != 1) throw new CandidateRejected("CANDIDATE_SCOPE_INVALID");
    }

    /** Called after the durable performance-row lock, so absence is an explicit expected initial head rather than an inference. */
    private UUID publicationHead(UUID tenantId, UUID performanceId) {
        List<String> heads = jdbc.query("""
                select h.version_id from td_bulk_ledger_publication_heads h join td_bulk_ledger_versions v on v.id=h.version_id
                where h.tenant_id=? and h.performance_id=? and v.status='PUBLISHED' for update
                """, (rs, n) -> rs.getString(1), tenantId.toString(), performanceId.toString());
        if (heads.size() > 1) throw new IllegalStateException("multiple publication heads");
        return heads.isEmpty() ? null : UUID.fromString(heads.getFirst());
    }

    private void finishVersion(Lease lease, UUID versionId) {
        transactions.executeWithoutResult(ignored -> {
            requireLease(lease);
            jdbc.update("update td_bulk_ledger_versions set status='READY' where id=? and status='PREPARING'", versionId.toString());
        });
    }

    private void markReady(Lease lease, int expectedVersions) {
        transactions.executeWithoutResult(ignored -> {
            requireLease(lease);
            Long notReady = jdbc.queryForObject("select count(*) from td_bulk_ledger_versions where candidate_import_id=? and status <> 'READY'", Long.class, lease.candidateId().toString());
            Long count = jdbc.queryForObject("select count(*) from td_bulk_ledger_versions where candidate_import_id=?", Long.class, lease.candidateId().toString());
            if (count == null || count != expectedVersions || (notReady != null && notReady > 0)) throw new IllegalStateException("candidate is incomplete");
            jdbc.update("update td_bulk_ledger_candidate_imports set status='READY', preparation_owner=null, preparation_lease_until=null, last_retry_code=null, updated_at=utc_timestamp(6) where id=? and preparation_fence_token=? and status='PREPARING'", lease.candidateId().toString(), lease.fence());
        });
    }

    private void retryable(Lease lease, String code) {
        jdbc.update("update td_bulk_ledger_candidate_imports set preparation_owner=null, preparation_lease_until=null, last_retry_code=?, updated_at=utc_timestamp(6) where id=? and preparation_fence_token=? and status='PREPARING'", code, lease.candidateId().toString(), lease.fence());
    }

    private void reject(Lease lease, String code) {
        transactions.executeWithoutResult(ignored -> {
            requireLease(lease);
            // These events belong only to this private candidate. Published/other-candidate history is never removed.
            jdbc.update("delete ce from td_bulk_ledger_change_events ce join td_bulk_ledger_versions v on v.id=ce.version_id where v.candidate_import_id=? and v.status <> 'PUBLISHED'", lease.candidateId().toString());
            jdbc.update("update td_bulk_ledger_versions set status='REJECTED' where candidate_import_id=? and status in ('PREPARING','READY')", lease.candidateId().toString());
            jdbc.update("update td_bulk_ledger_candidate_imports set status='REJECTED', failure_code=?, preparation_owner=null, preparation_lease_until=null, last_retry_code=null, updated_at=utc_timestamp(6) where id=? and preparation_fence_token=? and status='PREPARING'", code, lease.candidateId().toString(), lease.fence());
        });
    }

    private void requireLease(Lease lease) {
        List<Long> found = jdbc.query("select preparation_fence_token from td_bulk_ledger_candidate_imports where id=? and status='PREPARING' for update",
                (rs, n) -> rs.getLong(1), lease.candidateId().toString());
        if (found.size() != 1 || found.getFirst() != lease.fence()) throw new CandidateLeaseLost();
    }

    private record Version(UUID id, String status) { }
    private record Published(UUID id, long generation, long revision, String cursor, Long sequenceEnd, String sourceSystem) { }
    private record Event(String digest, UUID candidateId, String versionStatus) { }
    private record Lease(UUID candidateId, long fence) { }
    private record Row(long rowNo, String reservation, String ticket, String account, String keyVersion, java.time.LocalDateTime purchasedAt, int quantity,
                       String unit, String status, String sourceVersion, String eventId, java.time.LocalDateTime changedAt, Long sequence, String action, String digest) { }
    private static final class CandidateRejected extends RuntimeException { private CandidateRejected(String code) { this.code = code; } private final String code; }
    private static final class CandidateLeaseLost extends RuntimeException { }
}
