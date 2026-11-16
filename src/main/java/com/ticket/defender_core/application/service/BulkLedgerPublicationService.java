package com.ticket.defender_core.application.service;

import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** BL-05: the only path that switches publication heads. It never reads CSV/object storage or runs analysis. */
@Service
public class BulkLedgerPublicationService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final SyntheticReservationImportGate syntheticGate;

    public BulkLedgerPublicationService(JdbcTemplate jdbc, TransactionTemplate transactions, SyntheticReservationImportGate syntheticGate) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.syntheticGate = syntheticGate;
    }

    public Publication publish(UUID importId, UUID tenantId, UUID requesterMembershipId, String idempotencyKeyDigest) {
        return transactions.execute(ignored -> publishLocked(importId, tenantId, requesterMembershipId, idempotencyKeyDigest));
    }

    public Publication find(UUID importId, UUID tenantId) {
        List<PublicationRow> found = jdbc.query("""
                select id,import_id,tenant_id,published_at from td_bulk_ledger_publications
                where import_id=? and tenant_id=?
                """, (rs, n) -> new PublicationRow(uuid(rs.getString(1)), uuid(rs.getString(2)), uuid(rs.getString(3)),
                rs.getTimestamp(4).toLocalDateTime()), importId.toString(), tenantId.toString());
        if (found.isEmpty()) throw notFound();
        return publication(found.getFirst());
    }

    private Publication publishLocked(UUID importId, UUID tenantId, UUID requesterMembershipId, String keyDigest) {
        syntheticGate.requireSyntheticScope(tenantId, LocalDateTime.now(ZoneOffset.UTC));
        Job job = lockJob(importId, tenantId);
        PublicationRow sameKey = publicationByKey(requesterMembershipId, keyDigest);
        if (sameKey != null) {
            if (!sameKey.importId().equals(importId)) throw conflict("IDEMPOTENCY_KEY_REUSED");
            return publication(sameKey);
        }
        PublicationRow alreadyPublished = publicationByImport(importId, tenantId);
        if (alreadyPublished != null) return publication(alreadyPublished);
        if (!"v2".equals(job.schemaVersion()) || !"READY_TO_PUBLISH".equals(job.status()))
            throw conflict("LEDGER_PUBLICATION_NOT_READY");

        Candidate candidate = candidate(job);
        List<Scope> scopes = scopes(job.id());
        List<Version> versions = versions(candidate.id());
        if (scopes.isEmpty() || versions.size() != scopes.size()) throw conflict("LEDGER_PUBLICATION_SCOPE_MISMATCH");
        Map<UUID, Version> byPerformance = new HashMap<>();
        for (Version version : versions) {
            if (byPerformance.put(version.performanceId(), version) != null || !"READY".equals(version.status())
                    || !version.importId().equals(job.id()) || !version.tenantId().equals(job.tenantId())
                    || !version.eventId().equals(scopeEvent(scopes, version.performanceId())))
                throw conflict("LEDGER_PUBLICATION_CANDIDATE_INVALID");
        }
        for (Scope scope : scopes) if (!byPerformance.containsKey(scope.performanceId())) throw conflict("LEDGER_PUBLICATION_SCOPE_MISMATCH");

        // This deterministic lock is also the gap lock for a first publication: every performance exists.
        scopes.stream().sorted(Comparator.comparing(scope -> scope.performanceId().toString())).forEach(scope -> lockPerformance(scope, job));
        Map<UUID, Head> heads = new HashMap<>();
        for (Scope scope : scopes) heads.put(scope.performanceId(), currentHead(job.tenantId(), scope.performanceId()));
        for (Scope scope : scopes) validate(job, candidate, scope, byPerformance.get(scope.performanceId()), heads.get(scope.performanceId()));

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        UUID publicationId = UUID.randomUUID();
        String requestDigest = CandidatePipelineSupport.sha256("BL05", importId.toString());
        try {
            jdbc.update("insert into td_bulk_ledger_publications(id,import_id,tenant_id,requester_membership_id,idempotency_key_digest,request_digest,published_at) values(?,?,?,?,?,?,?)",
                    publicationId.toString(), importId.toString(), tenantId.toString(), requesterMembershipId.toString(), keyDigest, requestDigest, Timestamp.valueOf(now));
        } catch (DataIntegrityViolationException raced) {
            PublicationRow replay = publicationByKey(requesterMembershipId, keyDigest);
            if (replay != null && replay.importId().equals(importId)) return publication(replay);
            throw conflict("IDEMPOTENCY_KEY_REUSED");
        }
        List<PublishedScope> results = new ArrayList<>();
        for (Scope scope : scopes) {
            Version version = byPerformance.get(scope.performanceId());
            Head previous = heads.get(scope.performanceId());
            if (previous == null) {
                jdbc.update("insert into td_bulk_ledger_publication_heads(tenant_id,event_id,performance_id,version_id,published_at) values(?,?,?,?,?)",
                        tenantId.toString(), scope.eventId().toString(), scope.performanceId().toString(), version.id().toString(), Timestamp.valueOf(now));
            } else {
                int changed = jdbc.update("update td_bulk_ledger_publication_heads set version_id=?,published_at=? where tenant_id=? and performance_id=? and version_id=?",
                        version.id().toString(), Timestamp.valueOf(now), tenantId.toString(), scope.performanceId().toString(), previous.id().toString());
                if (changed != 1) throw conflict("LEDGER_PUBLICATION_CONCURRENT");
            }
            UUID historyId = UUID.randomUUID();
            jdbc.update("insert into td_bulk_ledger_publication_history(id,publication_id,import_id,tenant_id,event_id,performance_id,previous_version_id,version_id,generation_no,revision_no,source_system,source_batch_id,published_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    historyId.toString(), publicationId.toString(), importId.toString(), tenantId.toString(), scope.eventId().toString(), scope.performanceId().toString(),
                    previous == null ? null : previous.id().toString(), version.id().toString(), version.generation(), version.revision(), job.sourceSystem(), job.sourceBatchId(), Timestamp.valueOf(now));
            jdbc.update("insert into td_bulk_ledger_publication_outbox(id,publication_id,history_id,import_id,tenant_id,event_id,performance_id,version_id,deduplication_key,event_type,status,created_at) values(?,?,?,?,?,?,?,?,?, 'LEDGER_PUBLISHED','PENDING',?)",
                    UUID.randomUUID().toString(), publicationId.toString(), historyId.toString(), importId.toString(), tenantId.toString(), scope.eventId().toString(), scope.performanceId().toString(), version.id().toString(),
                    CandidatePipelineSupport.sha256("BL05_OUTBOX", publicationId.toString(), version.id().toString()), Timestamp.valueOf(now));
            results.add(new PublishedScope(scope.eventId(), scope.performanceId(), previous == null ? null : previous.id(), version.id(), version.generation(), version.revision(), true));
        }
        if (jdbc.update("update td_bulk_ledger_versions set status='PUBLISHED' where candidate_import_id=? and status='READY'", candidate.id().toString()) != versions.size())
            throw conflict("LEDGER_PUBLICATION_CANDIDATE_INVALID");
        if (jdbc.update("update td_bulk_ledger_candidate_imports set status='PUBLISHED',updated_at=? where id=? and status='READY'", Timestamp.valueOf(now), candidate.id().toString()) != 1)
            throw conflict("LEDGER_PUBLICATION_CANDIDATE_INVALID");
        if (jdbc.update("update td_bulk_import_jobs set status='PUBLISHED',updated_at=? where id=? and status='READY_TO_PUBLISH'", Timestamp.valueOf(now), importId.toString()) != 1)
            throw conflict("LEDGER_PUBLICATION_NOT_READY");
        return new Publication(publicationId, importId, job.sourceSystem(), job.sourceBatchId(), now.atOffset(ZoneOffset.UTC), results);
    }

    private void validate(Job job, Candidate candidate, Scope scope, Version version, Head current) {
        if (!candidate.objectVersion().equals(job.objectVersion()) || !candidate.objectSha256().equals(job.objectSha256())
                || !version.expectedHeadKnown()) throw conflict("LEDGER_PUBLICATION_EXPECTED_HEAD_UNKNOWN");
        UUID actualId = current == null ? null : current.id();
        if (!java.util.Objects.equals(actualId, version.expectedHeadVersionId())) throw conflict("LEDGER_PUBLICATION_HEAD_STALE");
        if (!job.sourceSystem().equals(version.sourceSystem()) || !job.sourceBatchId().equals(version.sourceBatchId())
                || !java.util.Objects.equals(scope.cursorEnd(), version.cursor()) || !java.util.Objects.equals(scope.sequenceEnd(), version.sequenceEnd()))
            throw conflict("LEDGER_PUBLICATION_SOURCE_MISMATCH");
        if ("FULL_SNAPSHOT".equals(job.mode())) {
            if (version.baseVersionId() != null || version.revision() != 0 || version.sourceAsOf() == null
                    || !version.sourceAsOf().equals(scope.asOf()))
                throw conflict("LEDGER_PUBLICATION_SNAPSHOT_STALE");
            if (current != null) validateSnapshotBaseline(job, scope, version, current);
        } else {
            if (current == null || version.baseVersionId() == null || !current.id().equals(version.baseVersionId())
                    || version.generation() != current.generation() || version.revision() != current.revision() + 1
                    || !job.sourceSystem().equals(current.sourceSystem()) || !java.util.Objects.equals(scope.cursorStart(), current.cursor())
                    || current.sequenceEnd() == null || scope.sequenceStart() == null || scope.sequenceStart() != current.sequenceEnd() + 1)
                throw conflict("LEDGER_PUBLICATION_CHANGE_BASELINE_STALE");
        }
    }

    /** A change revision has no snapshot as-of of its own, so use the published revision-zero ancestor as its provenance. */
    private void validateSnapshotBaseline(Job job, Scope scope, Version version, Head current) {
        // Source ordering is meaningful only when both the published head and candidate retain an authoritative checkpoint.
        if (current.sequenceEnd() == null || version.sequenceEnd() == null)
            throw conflict("LEDGER_PUBLICATION_SNAPSHOT_BASELINE_UNKNOWN");
        List<SnapshotBaseline> found = jdbc.query("""
                select source_system,source_as_of
                from td_bulk_ledger_versions
                where tenant_id=? and performance_id=? and generation_no=? and revision_no=0 and status='PUBLISHED'
                for update
                """, (rs, n) -> new SnapshotBaseline(rs.getString(1), local(rs.getTimestamp(2))),
                job.tenantId().toString(), scope.performanceId().toString(), current.generation());
        if (found.size() != 1 || found.getFirst().sourceAsOf() == null) throw conflict("LEDGER_PUBLICATION_SNAPSHOT_BASELINE_UNKNOWN");
        SnapshotBaseline baseline = found.getFirst();
        if (!current.sourceSystem().equals(baseline.sourceSystem()) || !job.sourceSystem().equals(baseline.sourceSystem()))
            throw conflict("LEDGER_PUBLICATION_SNAPSHOT_SOURCE_UNKNOWN");
        if (version.sourceAsOf().isBefore(baseline.sourceAsOf()) || version.sequenceEnd() < current.sequenceEnd())
            throw conflict("LEDGER_PUBLICATION_SNAPSHOT_STALE");
    }

    private Job lockJob(UUID importId, UUID tenantId) {
        List<Job> jobs = jdbc.query("select id,tenant_id,schema_version,status,mode,source_system,source_batch_id,declared_object_sha256,upload_object_version from td_bulk_import_jobs where id=? and tenant_id=? for update",
                (rs, n) -> new Job(uuid(rs.getString(1)), uuid(rs.getString(2)), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9)), importId.toString(), tenantId.toString());
        if (jobs.size() != 1) throw conflict("RESOURCE_NOT_FOUND");
        return jobs.getFirst();
    }

    private Candidate candidate(Job job) {
        List<Candidate> found = jdbc.query("select id,status,source_object_version,source_object_sha256 from td_bulk_ledger_candidate_imports where import_id=? and tenant_id=? for update",
                (rs, n) -> new Candidate(uuid(rs.getString(1)), rs.getString(2), rs.getString(3), rs.getString(4)), job.id().toString(), job.tenantId().toString());
        if (found.size() != 1 || !"READY".equals(found.getFirst().status()) || found.getFirst().objectVersion() == null || found.getFirst().objectSha256() == null)
            throw conflict("LEDGER_PUBLICATION_CANDIDATE_INVALID");
        return found.getFirst();
    }

    private List<Scope> scopes(UUID importId) {
        return jdbc.query("select event_id,performance_id,scope_as_of,cursor_start,cursor_end,source_sequence_start,source_sequence_end from td_bulk_import_scopes where job_id=? order by performance_id",
                (rs, n) -> new Scope(uuid(rs.getString(1)), uuid(rs.getString(2)), local(rs.getTimestamp(3)), rs.getString(4), rs.getString(5), longValue(rs.getObject(6)), longValue(rs.getObject(7))), importId.toString());
    }

    private List<Version> versions(UUID candidateId) {
        return jdbc.query("select id,import_id,tenant_id,event_id,performance_id,generation_no,revision_no,base_version_id,expected_head_known,expected_head_version_id,source_system,source_batch_id,source_as_of,source_cursor,source_sequence_end,status from td_bulk_ledger_versions where candidate_import_id=? order by performance_id",
                (rs, n) -> new Version(uuid(rs.getString(1)), uuid(rs.getString(2)), uuid(rs.getString(3)), uuid(rs.getString(4)), uuid(rs.getString(5)), rs.getLong(6), rs.getLong(7), nullableUuid(rs.getString(8)), rs.getBoolean(9), nullableUuid(rs.getString(10)), rs.getString(11), rs.getString(12), local(rs.getTimestamp(13)), rs.getString(14), longValue(rs.getObject(15)), rs.getString(16)), candidateId.toString());
    }

    private void lockPerformance(Scope scope, Job job) {
        List<Integer> found = jdbc.query("select 1 from verified_performances where id=? and tenant_id=? and event_id=? for update", (rs, n) -> rs.getInt(1), scope.performanceId().toString(), job.tenantId().toString(), scope.eventId().toString());
        if (found.size() != 1) throw conflict("LEDGER_PUBLICATION_SCOPE_MISMATCH");
    }

    private Head currentHead(UUID tenantId, UUID performanceId) {
        List<Head> found = jdbc.query("""
                select v.id,v.generation_no,v.revision_no,v.source_system,v.source_as_of,v.source_cursor,v.source_sequence_end
                from td_bulk_ledger_publication_heads h join td_bulk_ledger_versions v on v.id=h.version_id
                where h.tenant_id=? and h.performance_id=? and v.status='PUBLISHED' for update
                """, (rs, n) -> new Head(uuid(rs.getString(1)), rs.getLong(2), rs.getLong(3), rs.getString(4), local(rs.getTimestamp(5)), rs.getString(6), longValue(rs.getObject(7))), tenantId.toString(), performanceId.toString());
        if (found.size() > 1) throw conflict("LEDGER_PUBLICATION_HEAD_INVALID");
        return found.isEmpty() ? null : found.getFirst();
    }

    private PublicationRow publicationByKey(UUID member, String key) {
        List<PublicationRow> found = jdbc.query("select id,import_id,tenant_id,published_at from td_bulk_ledger_publications where requester_membership_id=? and idempotency_key_digest=?",
                (rs, n) -> new PublicationRow(uuid(rs.getString(1)), uuid(rs.getString(2)), uuid(rs.getString(3)), rs.getTimestamp(4).toLocalDateTime()), member.toString(), key);
        return found.isEmpty() ? null : found.getFirst();
    }

    private PublicationRow publicationByImport(UUID importId, UUID tenantId) {
        List<PublicationRow> found = jdbc.query("select id,import_id,tenant_id,published_at from td_bulk_ledger_publications where import_id=? and tenant_id=?",
                (rs, n) -> new PublicationRow(uuid(rs.getString(1)), uuid(rs.getString(2)), uuid(rs.getString(3)), rs.getTimestamp(4).toLocalDateTime()), importId.toString(), tenantId.toString());
        return found.isEmpty() ? null : found.getFirst();
    }

    private Publication publication(PublicationRow row) {
        List<PublishedScope> scopes = jdbc.query("""
                select h.event_id,h.performance_id,h.previous_version_id,h.version_id,h.generation_no,h.revision_no,
                       exists(select 1 from td_bulk_ledger_publication_outbox o where o.history_id=h.id and o.status='PENDING')
                from td_bulk_ledger_publication_history h where h.publication_id=? order by h.performance_id
                """, (rs, n) -> new PublishedScope(uuid(rs.getString(1)), uuid(rs.getString(2)), nullableUuid(rs.getString(3)), uuid(rs.getString(4)), rs.getLong(5), rs.getLong(6), rs.getBoolean(7)), row.id().toString());
        List<Source> source = jdbc.query("select source_system,source_batch_id from td_bulk_import_jobs where id=? and tenant_id=?",
                (rs, n) -> new Source(rs.getString(1), rs.getString(2)), row.importId().toString(), row.tenantId().toString());
        if (source.size() != 1) throw notFound();
        return new Publication(row.id(), row.importId(), source.getFirst().system(), source.getFirst().batch(), row.publishedAt().atOffset(ZoneOffset.UTC), scopes);
    }

    private UUID scopeEvent(List<Scope> scopes, UUID performance) {
        return scopes.stream().filter(scope -> scope.performanceId().equals(performance)).findFirst().map(Scope::eventId).orElse(null);
    }
    private static UUID uuid(String value) { return UUID.fromString(value); }
    private static UUID nullableUuid(String value) { return value == null ? null : uuid(value); }
    private static LocalDateTime local(Timestamp value) { return value == null ? null : value.toLocalDateTime(); }
    private static Long longValue(Object value) { return value == null ? null : ((Number) value).longValue(); }
    private static CaseApiException conflict(String code) { return new CaseApiException(HttpStatus.CONFLICT, code); }
    private static CaseApiException notFound() { return new CaseApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND"); }

    public record Publication(UUID publicationId, UUID importId, String sourceSystem, String sourceBatchId,
                              java.time.OffsetDateTime publishedAt, List<PublishedScope> scopes) { }
    public record PublishedScope(UUID eventId, UUID performanceId, UUID previousVersionId, UUID versionId, long generation, long revision, boolean outboxPending) { }
    private record Job(UUID id, UUID tenantId, String schemaVersion, String status, String mode, String sourceSystem, String sourceBatchId, String objectSha256, String objectVersion) { }
    private record Candidate(UUID id, String status, String objectVersion, String objectSha256) { }
    private record Scope(UUID eventId, UUID performanceId, LocalDateTime asOf, String cursorStart, String cursorEnd, Long sequenceStart, Long sequenceEnd) { }
    private record Version(UUID id, UUID importId, UUID tenantId, UUID eventId, UUID performanceId, long generation, long revision, UUID baseVersionId, boolean expectedHeadKnown, UUID expectedHeadVersionId, String sourceSystem, String sourceBatchId, LocalDateTime sourceAsOf, String cursor, Long sequenceEnd, String status) { }
    private record Head(UUID id, long generation, long revision, String sourceSystem, LocalDateTime sourceAsOf, String cursor, Long sequenceEnd) { }
    private record SnapshotBaseline(String sourceSystem, LocalDateTime sourceAsOf) { }
    private record PublicationRow(UUID id, UUID importId, UUID tenantId, LocalDateTime publishedAt) { }
    private record Source(String system, String batch) { }
}
