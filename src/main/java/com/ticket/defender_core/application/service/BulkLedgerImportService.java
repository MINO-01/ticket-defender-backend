package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.BulkLedgerImportDto;
import com.ticket.defender_core.adapter.out.persistence.AdminMembershipRepository;
import com.ticket.defender_core.adapter.out.persistence.BulkLedgerImportErrorRepository;
import com.ticket.defender_core.adapter.out.persistence.BulkLedgerImportJobRepository;
import com.ticket.defender_core.adapter.out.persistence.BulkLedgerImportScopeRepository;
import com.ticket.defender_core.adapter.out.persistence.MembershipEventGrantRepository;
import com.ticket.defender_core.adapter.out.persistence.MembershipRoleRepository;
import com.ticket.defender_core.adapter.out.persistence.VerifiedPerformanceRepository;
import com.ticket.defender_core.domain.AccessRecordStatus;
import com.ticket.defender_core.domain.AdminMembershipEntity;
import com.ticket.defender_core.domain.BulkLedgerImportError;
import com.ticket.defender_core.domain.BulkLedgerImportJob;
import com.ticket.defender_core.domain.BulkLedgerImportScope;
import com.ticket.defender_core.domain.VerifiedPerformance;
import com.ticket.defender_core.security.AdminAccessService;
import com.ticket.defender_core.security.AdminActor;
import com.ticket.defender_core.security.AdminRole;
import com.ticket.defender_core.security.TenantSelectionRequiredException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** BL-01 registration boundary. There is deliberately no upload, worker, staging, analysis, ledger publication, or retry command. */
@Service
@RequiredArgsConstructor
public class BulkLedgerImportService {
    private static final Set<AdminRole> VIEW_ROLES = Set.of(AdminRole.TENANT_ADMIN, AdminRole.ANALYST, AdminRole.REVIEWER, AdminRole.APPROVER);
    private static final Set<AdminRole> WRITE_ROLES = Set.of(AdminRole.TENANT_ADMIN);
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final AdminAccessService access;
    private final AdminMembershipRepository memberships;
    private final MembershipRoleRepository membershipRoles;
    private final MembershipEventGrantRepository eventGrants;
    private final VerifiedPerformanceRepository performances;
    private final SyntheticReservationImportGate syntheticGate;
    private final BulkLedgerImportJobRepository jobs;
    private final BulkLedgerImportScopeRepository scopes;
    private final BulkLedgerImportErrorRepository errors;
    private final BulkLedgerUploadStorage uploadStorage;
    private final BulkLedgerPublicationService publications;
    private final JdbcTemplate jdbc;
    @Value("${ticket-defender.bulk-ledger-upload.max-bytes:1073741824}") private long uploadMaximumBytes;
    @Value("${ticket-defender.bulk-ledger-upload.upload-ttl:PT10M}") private java.time.Duration uploadTtl;

    /** Canonical scope item. Length-prefixed digest components prevent cursor-boundary ambiguity. */
    public record ScopeDigest(UUID eventId, UUID performanceId, String scopeAsOf, String cursorStart, String cursorEnd,
                              Long sourceSequenceStart, Long sourceSequenceEnd, long declaredRows) {
        public ScopeDigest(UUID eventId, UUID performanceId, String scopeAsOf, String cursorStart, String cursorEnd, long declaredRows) {
            this(eventId, performanceId, scopeAsOf, cursorStart, cursorEnd, null, null, declaredRows);
        }
    }

    public static String requestDigest(String schemaVersion, String sourceSystem, String sourceBatchId, String mode,
            String format, String sha256, long bytes, long rows, boolean completeSnapshot, List<ScopeDigest> scopes) {
        List<String> canonical = new ArrayList<>(List.of("BL01", schemaVersion, sourceSystem, sourceBatchId, mode, format,
                sha256, Long.toString(bytes), Long.toString(rows), Boolean.toString(completeSnapshot)));
        scopes.stream().sorted(Comparator.comparing((ScopeDigest scope) -> scope.eventId().toString())
                        .thenComparing(scope -> scope.performanceId().toString()))
                .forEach(scope -> {
                    canonical.addAll(List.of(scope.eventId().toString(), scope.performanceId().toString(),
                            String.valueOf(scope.scopeAsOf()), String.valueOf(scope.cursorStart()), String.valueOf(scope.cursorEnd())));
                    // BL-01/v1 digests are persisted idempotency keys. Do not append v2-only null fields to their canonical form.
                    if ("v2".equals(schemaVersion)) canonical.addAll(List.of(String.valueOf(scope.sourceSequenceStart()), String.valueOf(scope.sourceSequenceEnd())));
                    canonical.add(Long.toString(scope.declaredRows()));
                });
        return CandidatePipelineSupport.sha256(canonical.toArray(String[]::new));
    }

    @Transactional
    public BulkLedgerImportDto.View register(String idempotencyKey, BulkLedgerImportDto.RegisterRequest input) {
        Normalized request = normalize(input);
        List<AuthorizedScope> authorized = authorizeScopes(request.scopes(), WRITE_ROLES);
        TenantScope actor = tenantScope(WRITE_ROLES);
        if (!actor.tenantId().equals(authorized.getFirst().tenantId()) || !actor.membershipId().equals(authorized.getFirst().membershipId()))
            throw notFound();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        // Check before replay too: a disabled or non-isolated environment must not expose this import workflow.
        syntheticGate.requireSyntheticScope(actor.tenantId(), now);
        String keyDigest = CandidatePipelineSupport.idempotencyDigest(idempotencyKey);
        var replay = jobs.findByCreatedByMembershipIdAndIdempotencyKeyDigest(actor.membershipId(), keyDigest);
        if (replay.isPresent()) return replay(replay.get(), request.requestDigest());
        var sameBatch = jobs.findByTenantIdAndSourceSystemAndSourceBatchId(actor.tenantId(), request.sourceSystem(), request.sourceBatchId());
        if (sameBatch.isPresent()) return replay(sameBatch.get(), request.requestDigest());

        BulkLedgerImportJob job = new BulkLedgerImportJob(actor.tenantId(), actor.membershipId(), keyDigest, request.requestDigest(),
                request.schemaVersion(), request.sourceSystem(), request.sourceBatchId(), request.mode(), request.format(),
                request.sha256(), request.bytes(), request.rows(), request.completeSnapshot(), now);
        try {
            jobs.saveAndFlush(job);
            scopes.saveAll(request.scopes().stream().map(scope -> new BulkLedgerImportScope(job.getId(), actor.tenantId(),
                    scope.eventId(), scope.performanceId(), scope.scopeAsOf(), scope.rows(), scope.cursorStart(), scope.cursorEnd(),
                    scope.sourceSequenceStart(), scope.sourceSequenceEnd())).toList());
            scopes.flush();
        } catch (DataIntegrityViolationException concurrent) {
            // The two UNIQUE indexes are the final cross-node protection. Do not turn a collision into a clean job.
            throw conflict("BULK_IMPORT_CONCURRENT_REGISTRATION");
        }
        return view(job, scopes.findByJobIdOrderByEventIdAscPerformanceIdAsc(job.getId()));
    }

    @Transactional(readOnly = true)
    public BulkLedgerImportDto.Page<BulkLedgerImportDto.View> list(int page, int pageSize) {
        validatePage(page, pageSize);
        TenantScope actor = tenantScope(VIEW_ROLES);
        List<BulkLedgerImportJob> found = jobs.findByTenantIdOrderByCreatedAtDescIdDesc(actor.tenantId());
        Map<UUID, List<BulkLedgerImportScope>> scopeMap = scopes(found);
        List<BulkLedgerImportDto.View> visible = found.stream().filter(job -> allowed(actor, scopeMap.getOrDefault(job.getId(), List.of())))
                .map(job -> view(job, scopeMap.getOrDefault(job.getId(), List.of()))).toList();
        int from = Math.min(page * pageSize, visible.size());
        int to = Math.min(from + pageSize, visible.size());
        return new BulkLedgerImportDto.Page<>(visible.subList(from, to), page, pageSize, to < visible.size());
    }

    @Transactional(readOnly = true)
    public BulkLedgerImportDto.View detail(UUID importId) {
        TenantScope actor = tenantScope(VIEW_ROLES);
        BulkLedgerImportJob job = jobs.findByIdAndTenantId(importId, actor.tenantId()).orElseThrow(BulkLedgerImportService::notFound);
        List<BulkLedgerImportScope> jobScopes = scopes.findByJobIdOrderByEventIdAscPerformanceIdAsc(job.getId());
        if (!allowed(actor, jobScopes)) throw notFound();
        return view(job, jobScopes);
    }

    @Transactional(readOnly = true)
    public BulkLedgerImportDto.Page<BulkLedgerImportDto.Error> errors(UUID importId, int page, int pageSize) {
        validatePage(page, pageSize);
        TenantScope actor = tenantScope(VIEW_ROLES);
        BulkLedgerImportJob job = jobs.findByIdAndTenantId(importId, actor.tenantId()).orElseThrow(BulkLedgerImportService::notFound);
        if (!allowed(actor, scopes.findByJobIdOrderByEventIdAscPerformanceIdAsc(job.getId()))) throw notFound();
        List<BulkLedgerImportDto.Error> found = errors.findByJobIdOrderBySequenceNoAsc(job.getId()).stream().map(BulkLedgerImportDto.Error::from).toList();
        int from = Math.min(page * pageSize, found.size()); int to = Math.min(from + pageSize, found.size());
        return new BulkLedgerImportDto.Page<>(found.subList(from, to), page, pageSize, to < found.size());
    }

    @Transactional
    public BulkLedgerImportDto.View cancel(UUID importId) {
        TenantScope actor = tenantScope(WRITE_ROLES);
        BulkLedgerImportJob job = jobs.findByIdAndTenantId(importId, actor.tenantId()).orElseThrow(BulkLedgerImportService::notFound);
        List<BulkLedgerImportScope> jobScopes = scopes.findByJobIdOrderByEventIdAscPerformanceIdAsc(job.getId());
        if (!allowed(actor, jobScopes)) throw notFound();
        try { job.cancel(LocalDateTime.now(ZoneOffset.UTC)); }
        catch (IllegalStateException invalid) { throw conflict("BULK_IMPORT_STATE_TRANSITION_INVALID"); }
        return view(jobs.saveAndFlush(job), jobScopes);
    }

    @Transactional
    public BulkLedgerImportDto.UploadSession uploadSession(UUID importId) {
        TenantScope actor = tenantScope(WRITE_ROLES);
        BulkLedgerImportJob job = jobs.findByIdAndTenantId(importId, actor.tenantId()).orElseThrow(BulkLedgerImportService::notFound);
        List<BulkLedgerImportScope> jobScopes = scopes.findByJobIdOrderByEventIdAscPerformanceIdAsc(job.getId());
        if (!allowed(actor, jobScopes)) throw notFound();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        syntheticGate.requireSyntheticScope(actor.tenantId(), now);
        if (job.getDeclaredFileBytes() > uploadMaximumBytes) throw new CaseApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BULK_UPLOAD_TOO_LARGE");
        java.time.Instant expires = now.atOffset(ZoneOffset.UTC).toInstant().plus(uploadTtl);
        String key = objectKey(job.getId());
        try {
            job.beginUpload(CandidatePipelineSupport.sha256(key), expires.atOffset(ZoneOffset.UTC).toLocalDateTime(), now);
            BulkLedgerUploadStorage.UploadSession session = uploadStorage.createUploadSession(key, job.getDeclaredFileBytes(), expires);
            jobs.saveAndFlush(job);
            return new BulkLedgerImportDto.UploadSession(session.uploadUrl(), session.expiresAt().atOffset(ZoneOffset.UTC));
        } catch (IllegalStateException invalid) {
            throw conflict("BULK_IMPORT_STATE_TRANSITION_INVALID");
        } catch (BulkLedgerUploadStorage.Unavailable unavailable) {
            throw new CaseApiException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE");
        }
    }

    @Transactional
    public BulkLedgerImportDto.View completeUpload(UUID importId) {
        TenantScope actor = tenantScope(WRITE_ROLES);
        BulkLedgerImportJob job = jobs.findByIdAndTenantId(importId, actor.tenantId()).orElseThrow(BulkLedgerImportService::notFound);
        List<BulkLedgerImportScope> jobScopes = scopes.findByJobIdOrderByEventIdAscPerformanceIdAsc(job.getId());
        if (!allowed(actor, jobScopes)) throw notFound();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        syntheticGate.requireSyntheticScope(actor.tenantId(), now);
        try {
            BulkLedgerUploadStorage.VerifiedObject object = uploadStorage.verify(objectKey(job.getId()), null, uploadMaximumBytes);
            if (object.byteSize() != job.getDeclaredFileBytes() || !job.getDeclaredObjectSha256().equals(object.sha256()))
                throw new CaseApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BULK_UPLOAD_INTEGRITY_MISMATCH");
            job.completeUpload(object.versionId(), now);
            BulkLedgerImportJob saved = jobs.saveAndFlush(job);
            // A separately scheduled, DB-claimed worker processes this only after this HTTP transaction commits.
            return view(saved, jobScopes);
        } catch (BulkLedgerUploadStorage.ObjectChanged | BulkLedgerUploadStorage.InvalidObject failure) {
            throw new CaseApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BULK_UPLOAD_OBJECT_INVALID");
        } catch (IllegalStateException invalid) {
            throw conflict("BULK_IMPORT_STATE_TRANSITION_INVALID");
        } catch (BulkLedgerUploadStorage.Unavailable failure) {
            throw new CaseApiException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE");
        }
    }

    /** The client selects only an import. Server-owned scope rows determine every published performance. */
    public BulkLedgerImportDto.PublicationView publish(UUID importId, String idempotencyKey) {
        TenantScope actor = tenantScope(WRITE_ROLES);
        BulkLedgerImportJob job = jobs.findByIdAndTenantId(importId, actor.tenantId()).orElseThrow(BulkLedgerImportService::notFound);
        List<BulkLedgerImportScope> jobScopes = scopes.findByJobIdOrderByEventIdAscPerformanceIdAsc(job.getId());
        if (!allowed(actor, jobScopes)) throw notFound();
        var published = publications.publish(importId, actor.tenantId(), actor.membershipId(), CandidatePipelineSupport.idempotencyDigest(idempotencyKey));
        return publicationView(published);
    }

    @Transactional(readOnly = true)
    public BulkLedgerImportDto.PublicationView publication(UUID importId) {
        TenantScope actor = tenantScope(VIEW_ROLES);
        BulkLedgerImportJob job = jobs.findByIdAndTenantId(importId, actor.tenantId()).orElseThrow(BulkLedgerImportService::notFound);
        List<BulkLedgerImportScope> jobScopes = scopes.findByJobIdOrderByEventIdAscPerformanceIdAsc(job.getId());
        if (!allowed(actor, jobScopes)) throw notFound();
        return publicationView(publications.find(importId, actor.tenantId()));
    }

    /**
     * Reads the private candidate rows for every declared import scope. It deliberately takes no locks and performs
     * no repair: this preview is informational and the publication transaction remains the authority for eligibility.
     */
    @Transactional(readOnly = true)
    public BulkLedgerImportDto.CandidatePreviewView candidates(UUID importId) {
        TenantScope actor = tenantScope(WRITE_ROLES);
        BulkLedgerImportJob job = jobs.findByIdAndTenantId(importId, actor.tenantId()).orElseThrow(BulkLedgerImportService::notFound);
        List<BulkLedgerImportScope> jobScopes = scopes.findByJobIdOrderByEventIdAscPerformanceIdAsc(job.getId());
        if (!allowed(actor, jobScopes)) throw notFound();

        List<CandidateImport> candidates = jdbc.query("""
                select id,status,failure_code from td_bulk_ledger_candidate_imports
                where import_id=? and tenant_id=?
                """, (rs, row) -> new CandidateImport(UUID.fromString(rs.getString("id")),
                BulkLedgerImportDto.CandidatePreparationStatus.valueOf(rs.getString("status")), rs.getString("failure_code")),
                importId.toString(), actor.tenantId().toString());
        if (candidates.size() > 1) throw new IllegalStateException("candidate import uniqueness violated");
        if (candidates.isEmpty()) return candidatePreview(importId, BulkLedgerImportDto.CandidatePreparationStatus.NOT_STARTED,
                BulkLedgerImportDto.CandidateDataStatus.NOT_AVAILABLE, null, jobScopes, Map.of());

        CandidateImport candidate = candidates.getFirst();
        Map<ScopeKey, CandidateVersion> versions = new HashMap<>();
        boolean duplicateOrForeignScope = false;
        for (CandidateVersion version : jdbc.query("""
                select v.event_id,v.performance_id,v.id,v.generation_no,v.revision_no,v.base_version_id,
                       v.expected_head_known,v.expected_head_version_id,v.status,h.version_id as current_head_version_id
                from td_bulk_ledger_versions v
                left join td_bulk_ledger_publication_heads h
                  on h.tenant_id=v.tenant_id and h.performance_id=v.performance_id
                where v.candidate_import_id=? and v.tenant_id=?
                """, (rs, row) -> new CandidateVersion(new ScopeKey(uuid(rs, "event_id"), uuid(rs, "performance_id")),
                uuid(rs, "id"), rs.getLong("generation_no"), rs.getLong("revision_no"), uuid(rs, "base_version_id"),
                rs.getBoolean("expected_head_known"), uuid(rs, "expected_head_version_id"), uuid(rs, "current_head_version_id"),
                rs.getString("status")), candidate.id().toString(), actor.tenantId().toString())) {
            if (versions.put(version.scope(), version) != null) duplicateOrForeignScope = true;
        }
        Set<ScopeKey> expected = jobScopes.stream().map(scope -> new ScopeKey(scope.getEventId(), scope.getPerformanceId()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!expected.equals(versions.keySet())) duplicateOrForeignScope = true;
        BulkLedgerImportDto.CandidateDataStatus dataStatus = candidateDataStatus(candidate.status(), versions.size(), jobScopes.size(), duplicateOrForeignScope);
        return candidatePreview(importId, candidate.status(), dataStatus, candidate.failureCode(), jobScopes, versions);
    }

    private static BulkLedgerImportDto.CandidateDataStatus candidateDataStatus(BulkLedgerImportDto.CandidatePreparationStatus status,
            int versionCount, int expectedCount, boolean invalid) {
        if (invalid) return BulkLedgerImportDto.CandidateDataStatus.INCOMPLETE;
        if (status == BulkLedgerImportDto.CandidatePreparationStatus.PREPARING && versionCount < expectedCount)
            return BulkLedgerImportDto.CandidateDataStatus.PREPARING;
        if (status == BulkLedgerImportDto.CandidatePreparationStatus.NOT_STARTED) return BulkLedgerImportDto.CandidateDataStatus.NOT_AVAILABLE;
        return BulkLedgerImportDto.CandidateDataStatus.COMPLETE;
    }

    private static BulkLedgerImportDto.CandidatePreviewView candidatePreview(UUID importId,
            BulkLedgerImportDto.CandidatePreparationStatus status, BulkLedgerImportDto.CandidateDataStatus dataStatus,
            String failureCode, List<BulkLedgerImportScope> scopes, Map<ScopeKey, CandidateVersion> versions) {
        return new BulkLedgerImportDto.CandidatePreviewView(importId, status, dataStatus, failureCode,
                scopes.stream().map(scope -> candidateScope(scope, versions.get(new ScopeKey(scope.getEventId(), scope.getPerformanceId())))).toList());
    }

    private static BulkLedgerImportDto.CandidateScope candidateScope(BulkLedgerImportScope scope, CandidateVersion version) {
        if (version == null) return new BulkLedgerImportDto.CandidateScope(scope.getEventId(), scope.getPerformanceId(),
                null, null, null, null, null, null, null, BulkLedgerImportDto.CandidateHeadStatus.UNAVAILABLE, null);
        BulkLedgerImportDto.CandidateHeadStatus headStatus = !version.expectedHeadKnown()
                ? BulkLedgerImportDto.CandidateHeadStatus.UNKNOWN
                : java.util.Objects.equals(version.expectedHeadVersionId(), version.currentHeadVersionId())
                        ? BulkLedgerImportDto.CandidateHeadStatus.CURRENT : BulkLedgerImportDto.CandidateHeadStatus.CHANGED;
        return new BulkLedgerImportDto.CandidateScope(scope.getEventId(), scope.getPerformanceId(), version.id(),
                version.generation(), version.revision(), version.baseVersionId(), version.expectedHeadKnown(),
                version.expectedHeadVersionId(), version.currentHeadVersionId(), headStatus, version.status());
    }

    private static UUID uuid(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        String value = rs.getString(column);
        return value == null ? null : UUID.fromString(value);
    }

    private static BulkLedgerImportDto.PublicationView publicationView(BulkLedgerPublicationService.Publication source) {
        return new BulkLedgerImportDto.PublicationView(source.publicationId(), source.importId(), source.sourceSystem(), source.sourceBatchId(), source.publishedAt(), source.scopes().stream()
                .map(scope -> new BulkLedgerImportDto.PublishedScope(scope.eventId(), scope.performanceId(), scope.previousVersionId(),
                        scope.versionId(), scope.generation(), scope.revision(), scope.outboxPending())).toList());
    }

    private static String objectKey(UUID id) { return "bulk-ledger-private/" + id + "/upload.csv"; }

    private List<AuthorizedScope> authorizeScopes(List<NormalizedScope> input, Set<AdminRole> requiredRoles) {
        List<AuthorizedScope> result = new ArrayList<>();
        UUID tenant = null; UUID membership = null;
        for (NormalizedScope scope : input) {
            AdminActor actor = access.requireEventAccess(scope.eventId(), requiredRoles);
            AdminMembershipEntity member = memberships.findBySubjectAndTenantId(actor.subject(), actor.tenantId())
                    .filter(found -> AccessRecordStatus.ACTIVE.name().equals(found.getStatus())).orElseThrow(BulkLedgerImportService::notFound);
            if (tenant != null && !tenant.equals(actor.tenantId())) throw CandidatePipelineSupport.invalid();
            if (membership != null && !membership.equals(member.getId())) throw notFound();
            tenant = actor.tenantId(); membership = member.getId();
            VerifiedPerformance performance = performances.lockByIdAndScope(scope.performanceId(), actor.tenantId(), scope.eventId())
                    .orElseThrow(BulkLedgerImportService::notFound);
            if (performance.getStatus() != VerifiedPerformance.Status.ACTIVE) throw conflict("PERFORMANCE_WITHDRAWN");
            result.add(new AuthorizedScope(tenant, membership));
        }
        return result;
    }

    private TenantScope tenantScope(Set<AdminRole> permitted) {
        String subject = access.authenticatedSubject();
        List<AdminMembershipEntity> active = memberships.findActiveBySubject(subject, Pageable.unpaged()).stream()
                .filter(member -> subject.equals(member.getSubject())).toList();
        if (active.size() > 1) throw new TenantSelectionRequiredException();
        AdminMembershipEntity member = active.stream().findFirst().orElseThrow(BulkLedgerImportService::notFound);
        Set<AdminRole> roles = new HashSet<>();
        membershipRoles.findAllByMembershipId(member.getId()).forEach(role -> roles.add(AdminRole.valueOf(role.getRole())));
        if (roles.stream().noneMatch(permitted::contains)) throw new org.springframework.security.access.AccessDeniedException("Required role is missing");
        Set<UUID> events = eventGrants.findAllByMembershipIdAndTenantId(member.getId(), member.getTenantId()).stream()
                .map(grant -> grant.getEventId()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new TenantScope(member.getTenantId(), member.getId(), events);
    }

    private static boolean allowed(TenantScope actor, List<BulkLedgerImportScope> jobScopes) {
        return !jobScopes.isEmpty() && jobScopes.stream().allMatch(scope -> actor.eventIds().contains(scope.getEventId()));
    }
    private Map<UUID, List<BulkLedgerImportScope>> scopes(List<BulkLedgerImportJob> jobs) {
        if (jobs.isEmpty()) return Map.of();
        Map<UUID, List<BulkLedgerImportScope>> result = new HashMap<>();
        scopes.findByJobIdInOrderByEventIdAscPerformanceIdAsc(jobs.stream().map(BulkLedgerImportJob::getId).toList())
                .forEach(scope -> result.computeIfAbsent(scope.getJobId(), ignored -> new ArrayList<>()).add(scope));
        return result;
    }
    private static BulkLedgerImportDto.View view(BulkLedgerImportJob job, List<BulkLedgerImportScope> scopes) {
        return new BulkLedgerImportDto.View(job.getId(), job.getSchemaVersion(), job.getSourceSystem(), job.getSourceBatchId(),
                job.getMode(), job.getFormat(), job.getDeclaredObjectSha256(), job.getDeclaredFileBytes(), job.getDeclaredRowCount(),
                job.isCompleteSnapshot(), job.getStatus(), job.getProcessedRowCount(), job.getAcceptedRowCount(), job.getRejectedRowCount(),
                scopes.stream().map(BulkLedgerImportDto.Scope::from).toList(), offset(job.getCreatedAt()), offset(job.getUpdatedAt()), offset(job.getCancelledAt()));
    }
    private BulkLedgerImportDto.View replay(BulkLedgerImportJob job, String digest) {
        if (!job.getRequestDigest().equals(digest)) throw conflict("BULK_IMPORT_IDEMPOTENCY_CONFLICT");
        return view(job, scopes.findByJobIdOrderByEventIdAscPerformanceIdAsc(job.getId()));
    }
    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) throw CandidatePipelineSupport.invalid();
    }
    private static Normalized normalize(BulkLedgerImportDto.RegisterRequest request) {
        if (request == null || request.declaredFileBytes() == null || request.declaredRowCount() == null || request.completeSnapshot() == null || request.scopes() == null || request.scopes().isEmpty())
            throw CandidatePipelineSupport.invalid();
        String schema = CandidatePipelineSupport.bounded(request.schemaVersion(), 32);
        if (!"v1".equals(schema) && !"v2".equals(schema)) throw CandidatePipelineSupport.invalid();
        String system = CandidatePipelineSupport.bounded(request.sourceSystem(), 64);
        String batch = CandidatePipelineSupport.bounded(request.sourceBatchId(), 128);
        BulkLedgerImportJob.Mode mode = enumValue(BulkLedgerImportJob.Mode.class, request.mode());
        BulkLedgerImportJob.Format format = enumValue(BulkLedgerImportJob.Format.class, request.format());
        String sha = request.declaredObjectSha256() == null ? null : request.declaredObjectSha256().strip().toLowerCase(Locale.ROOT);
        if (mode == null || format == null || sha == null || !SHA256.matcher(sha).matches() || request.declaredFileBytes() < 0 || request.declaredRowCount() < 0)
            throw CandidatePipelineSupport.invalid();
        if ((mode == BulkLedgerImportJob.Mode.FULL_SNAPSHOT) != request.completeSnapshot()) throw CandidatePipelineSupport.invalid();
        List<NormalizedScope> scopes = new ArrayList<>(); Set<String> unique = new HashSet<>(); long total = 0;
        for (BulkLedgerImportDto.ScopeRequest scope : request.scopes()) {
            if (scope == null || scope.eventId() == null || scope.performanceId() == null || scope.declaredRowCount() == null || scope.declaredRowCount() < 0)
                throw CandidatePipelineSupport.invalid();
            LocalDateTime asOf = scope.scopeAsOf() == null ? null : scope.scopeAsOf().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
            String start = optional(scope.cursorStart(), 256); String end = optional(scope.cursorEnd(), 256);
            Long sequenceStart = scope.sourceSequenceStart(), sequenceEnd = scope.sourceSequenceEnd();
            if ("v1".equals(schema)) {
                if (mode == BulkLedgerImportJob.Mode.FULL_SNAPSHOT && (asOf == null || start != null || end != null || sequenceStart != null || sequenceEnd != null)) throw CandidatePipelineSupport.invalid();
                if (mode == BulkLedgerImportJob.Mode.CHANGE_FEED && (asOf != null || start == null || end == null || sequenceStart != null || sequenceEnd != null)) throw CandidatePipelineSupport.invalid();
            } else if (mode == BulkLedgerImportJob.Mode.FULL_SNAPSHOT) {
                if (asOf == null || start != null || end == null || sequenceStart != null || sequenceEnd == null || sequenceEnd < 0) throw CandidatePipelineSupport.invalid();
            } else {
                if (asOf != null || start == null || end == null || sequenceStart == null || sequenceEnd == null
                        || sequenceStart < 0 || sequenceEnd < sequenceStart || scope.declaredRowCount() == 0
                        || sequenceEnd - sequenceStart + 1 != scope.declaredRowCount()) throw CandidatePipelineSupport.invalid();
            }
            if (!unique.add(scope.eventId() + ":" + scope.performanceId())) throw CandidatePipelineSupport.invalid();
            try { total = Math.addExact(total, scope.declaredRowCount()); } catch (ArithmeticException overflow) { throw CandidatePipelineSupport.invalid(); }
            scopes.add(new NormalizedScope(scope.eventId(), scope.performanceId(), asOf, scope.declaredRowCount(), start, end, sequenceStart, sequenceEnd));
        }
        if (total != request.declaredRowCount()) throw CandidatePipelineSupport.invalid();
        List<ScopeDigest> digestScopes = scopes.stream().map(scope -> new ScopeDigest(scope.eventId(), scope.performanceId(),
                scope.scopeAsOf() == null ? null : scope.scopeAsOf().atOffset(ZoneOffset.UTC).toString(),
                scope.cursorStart(), scope.cursorEnd(), scope.sourceSequenceStart(), scope.sourceSequenceEnd(), scope.rows())).toList();
        return new Normalized(schema, system, batch, mode, format, sha, request.declaredFileBytes(), request.declaredRowCount(), request.completeSnapshot(), scopes,
                requestDigest(schema, system, batch, mode.name(), format.name(), sha, request.declaredFileBytes(), request.declaredRowCount(), request.completeSnapshot(), digestScopes));
    }
    private static String optional(String value, int max) {
        if (value == null) return null;
        String normalized = value.strip();
        if (normalized.isBlank() || normalized.length() > max) throw CandidatePipelineSupport.invalid();
        return normalized;
    }
    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        if (value == null) return null;
        try { return Enum.valueOf(type, value); } catch (IllegalArgumentException ignored) { return null; }
    }
    private static OffsetDateTime offset(LocalDateTime value) { return value == null ? null : value.atOffset(ZoneOffset.UTC); }
    private static CaseApiException conflict(String code) { return new CaseApiException(HttpStatus.CONFLICT, code); }
    private static CaseApiException notFound() { return new CaseApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND"); }
    private record Normalized(String schemaVersion, String sourceSystem, String sourceBatchId, BulkLedgerImportJob.Mode mode,
                              BulkLedgerImportJob.Format format, String sha256, long bytes, long rows, boolean completeSnapshot,
                              List<NormalizedScope> scopes, String requestDigest) { }
    private record NormalizedScope(UUID eventId, UUID performanceId, LocalDateTime scopeAsOf, long rows, String cursorStart, String cursorEnd,
                                   Long sourceSequenceStart, Long sourceSequenceEnd) { }
    private record AuthorizedScope(UUID tenantId, UUID membershipId) { }
    private record TenantScope(UUID tenantId, UUID membershipId, Set<UUID> eventIds) { }
    private record ScopeKey(UUID eventId, UUID performanceId) { }
    private record CandidateImport(UUID id, BulkLedgerImportDto.CandidatePreparationStatus status, String failureCode) { }
    private record CandidateVersion(ScopeKey scope, UUID id, long generation, long revision, UUID baseVersionId,
                                    boolean expectedHeadKnown, UUID expectedHeadVersionId, UUID currentHeadVersionId,
                                    String status) { }
}
