package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.CasePreparationDto;
import com.ticket.defender_core.adapter.out.persistence.CaseIdempotencyRepository;
import com.ticket.defender_core.adapter.out.persistence.CaseRepository;
import com.ticket.defender_core.adapter.out.persistence.CaseSourceReferenceRepository;
import com.ticket.defender_core.adapter.out.persistence.EvidenceSnapshotRepository;
import com.ticket.defender_core.adapter.out.persistence.ReportVersionRepository;
import com.ticket.defender_core.domain.Case;
import com.ticket.defender_core.domain.CaseIdempotencyRecord;
import com.ticket.defender_core.domain.EvidenceSnapshot;
import com.ticket.defender_core.domain.ReportVersion;
import com.ticket.defender_core.security.AdminAccessService;
import com.ticket.defender_core.security.AdminResourceScope;
import com.ticket.defender_core.security.AdminRole;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class CasePreparationService {
    private static final Pattern KEY = Pattern.compile(
            "(?i)[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    private static final Set<AdminRole> REVIEWER = Set.of(AdminRole.REVIEWER);

    private final CaseService caseService;
    private final AdminAccessService access;
    private final CaseMembershipAccess memberships;
    private final CasePreparationWriteService writer;
    private final CaseRepository cases;
    private final CaseSourceReferenceRepository sources;
    private final CaseSnapshotManifest manifests;
    private final EvidenceSnapshotRepository snapshots;
    private final ReportVersionRepository drafts;
    private final CaseIdempotencyRepository idempotency;
    private final ObjectMapper json;

    public CasePreparationDto.Snapshot capture(UUID caseId, CasePreparationDto.Capture input, String key) {
        if (input == null || input.expectedCaseVersion() == null) throw invalid();
        Authorized actor = reviewer(caseId);
        Prepared prepared = prepare(key, input);
        CasePreparationDto.Snapshot previous = replaySnapshot(caseId, actor, prepared);
        if (previous != null) return previous;
        try {
            CasePreparationWriteService.validate(input.reason(), 3, 1800);
            Case target = preflightCase(caseId, actor, input.expectedCaseVersion());
            if (snapshots.findByCaseIdAndEvidenceRevision(caseId, target.getEvidenceRevision()).isPresent())
                throw conflict("SNAPSHOT_ALREADY_EXISTS");
            CaseSnapshotManifest.Captured manifest = manifests.capture(target,
                    sources.findByCaseIdOrderByLinkedAtAscIdAsc(caseId), LocalDateTime.now(ZoneOffset.UTC));
            return writer.capture(caseId, input, actor.memberId(), prepared.keyDigest(),
                    prepared.requestDigest(), manifest);
        } catch (CaseApiException collision) {
            CasePreparationDto.Snapshot committed = replaySnapshot(caseId, reviewer(caseId), prepared);
            if (committed != null) return committed;
            throw collision;
        } catch (DataIntegrityViolationException collision) {
            CasePreparationDto.Snapshot committed = replaySnapshot(caseId, reviewer(caseId), prepared);
            if (committed != null) return committed;
            Case current = cases.findById(caseId).orElseThrow(CasePreparationService::notFound);
            if (snapshots.findByCaseIdAndEvidenceRevision(caseId, current.getEvidenceRevision()).isPresent())
                throw conflict("SNAPSHOT_ALREADY_EXISTS");
            throw collision;
        } catch (OptimisticLockingFailureException collision) {
            CasePreparationDto.Snapshot committed = replaySnapshot(caseId, reviewer(caseId), prepared);
            if (committed != null) return committed;
            throw conflict("CASE_VERSION_CONFLICT");
        }
    }

    public CasePreparationDto.Draft draft(UUID caseId, CasePreparationDto.DraftInput input, String key) {
        if (input == null || input.expectedCaseVersion() == null || input.evidenceSnapshotId() == null)
            throw invalid();
        Authorized actor = reviewer(caseId);
        Prepared prepared = prepare(key, input);
        CasePreparationDto.Draft previous = replayDraft(caseId, actor, prepared);
        if (previous != null) return previous;
        try {
            CasePreparationWriteService.validate(input.title(), 3, 160);
            CasePreparationWriteService.validate(input.assessment(), 10, 6000);
            CasePreparationWriteService.validate(input.limitations(), 3, 3000);
            CasePreparationWriteService.validate(input.conclusion(), 3, 3000);
            CasePreparationWriteService.validate(input.reason(), 3, 1800);
            Case target = preflightCase(caseId, actor, input.expectedCaseVersion());
            EvidenceSnapshot snapshot = snapshots.findById(input.evidenceSnapshotId())
                    .orElseThrow(CasePreparationService::notFound);
            if (!target.getTenantId().equals(snapshot.getTenantId())
                    || !target.getEventId().equals(snapshot.getEventId())
                    || !caseId.equals(snapshot.getCaseId())) throw notFound();
            if (snapshot.getEvidenceRevision() != target.getEvidenceRevision())
                throw conflict("EVIDENCE_VERSION_MISMATCH");
            CaseSnapshotManifest.Captured manifest = manifests.capture(target,
                    sources.findByCaseIdOrderByLinkedAtAscIdAsc(caseId), LocalDateTime.now(ZoneOffset.UTC));
            if (!manifest.sha256().equals(snapshot.getManifestSha256())) throw conflict("SOURCE_CHANGED");
            return writer.draft(caseId, input, actor.memberId(), prepared.keyDigest(),
                    prepared.requestDigest(), manifest);
        } catch (CaseApiException collision) {
            CasePreparationDto.Draft committed = replayDraft(caseId, reviewer(caseId), prepared);
            if (committed != null) return committed;
            throw collision;
        } catch (DataIntegrityViolationException collision) {
            CasePreparationDto.Draft committed = replayDraft(caseId, reviewer(caseId), prepared);
            if (committed != null) return committed;
            Case current = cases.findById(caseId).orElseThrow(CasePreparationService::notFound);
            if (!input.expectedCaseVersion().equals(current.getCaseVersion()))
                throw conflict("CASE_VERSION_CONFLICT");
            throw collision;
        } catch (OptimisticLockingFailureException collision) {
            CasePreparationDto.Draft committed = replayDraft(caseId, reviewer(caseId), prepared);
            if (committed != null) return committed;
            throw conflict("CASE_VERSION_CONFLICT");
        }
    }

    @Transactional(readOnly = true)
    public CasePreparationDto.SnapshotPage snapshots(UUID caseId, int page, int pageSize) {
        page(page, pageSize);
        caseService.detail(caseId);
        var found = snapshots.findByCaseId(caseId,
                PageRequest.of(page, pageSize, Sort.by("evidenceRevision").descending()));
        return new CasePreparationDto.SnapshotPage(found.stream()
                .map(CasePreparationDto.Snapshot::from).toList(), page, pageSize, found.hasNext());
    }

    @Transactional(readOnly = true)
    public CasePreparationDto.Snapshot snapshot(UUID caseId, UUID snapshotId) {
        caseService.detail(caseId);
        EvidenceSnapshot snapshot = snapshots.findById(snapshotId).orElseThrow(CasePreparationService::notFound);
        if (!caseId.equals(snapshot.getCaseId())) throw notFound();
        return CasePreparationDto.Snapshot.from(snapshot);
    }

    @Transactional(readOnly = true)
    public CasePreparationDto.DraftPage drafts(UUID caseId, int page, int pageSize) {
        page(page, pageSize);
        caseService.detail(caseId);
        var found = drafts.findByCaseId(caseId,
                PageRequest.of(page, pageSize, Sort.by("revision").descending()));
        return new CasePreparationDto.DraftPage(found.stream()
                .map(CasePreparationDto.Draft::from).toList(), page, pageSize, found.hasNext());
    }

    @Transactional(readOnly = true)
    public CasePreparationDto.Draft draft(UUID draftId) {
        ReportVersion draft = drafts.findById(draftId).orElseThrow(CasePreparationService::notFound);
        caseService.detail(draft.getCaseId());
        Case target = cases.findById(draft.getCaseId()).orElseThrow(CasePreparationService::notFound);
        if (!target.getTenantId().equals(draft.getTenantId())
                || !target.getEventId().equals(draft.getEventId())) throw notFound();
        return CasePreparationDto.Draft.from(draft);
    }

    private Authorized reviewer(UUID caseId) {
        Case target = cases.findById(caseId).orElseThrow(CasePreparationService::notFound);
        var actor = access.requireResourceAccess(
                new AdminResourceScope(target.getTenantId(), target.getEventId()), null, REVIEWER);
        return new Authorized(target.getTenantId(), target.getEventId(), memberships.actor(actor).getId());
    }

    private Case preflightCase(UUID caseId, Authorized actor, Long expected) {
        Case target = cases.findById(caseId).orElseThrow(CasePreparationService::notFound);
        if (!actor.tenantId().equals(target.getTenantId())
                || !actor.eventId().equals(target.getEventId())) throw notFound();
        if (!Objects.equals(expected, target.getCaseVersion())) throw conflict("CASE_VERSION_CONFLICT");
        if (target.getStatus() != Case.Status.IN_REVIEW) throw conflict("CASE_STATE_CONFLICT");
        return target;
    }

    private Prepared prepare(String key, Object request) {
        if (key == null || !KEY.matcher(key).matches())
            throw new CaseApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY");
        return new Prepared(CaseSnapshotManifest.sha256(key.toLowerCase()),
                CaseSnapshotManifest.sha256(json.writeValueAsString(request)));
    }

    private CasePreparationDto.Snapshot replaySnapshot(UUID caseId, Authorized actor, Prepared prepared) {
        CaseIdempotencyRecord record = replayRecord(caseId, actor, "SNAPSHOT_CREATE", prepared);
        if (record == null) return null;
        SnapshotReference result = json.readValue(record.getResponseJson(), SnapshotReference.class);
        EvidenceSnapshot snapshot = snapshots.findById(result.evidenceSnapshotId())
                .orElseThrow(CasePreparationService::notFound);
        if (!sameScope(caseId, actor, snapshot.getCaseId(), snapshot.getTenantId(), snapshot.getEventId()))
            throw notFound();
        return CasePreparationDto.Snapshot.from(snapshot);
    }

    private CasePreparationDto.Draft replayDraft(UUID caseId, Authorized actor, Prepared prepared) {
        CaseIdempotencyRecord record = replayRecord(caseId, actor, "DRAFT_CREATE", prepared);
        if (record == null) return null;
        DraftReference result = json.readValue(record.getResponseJson(), DraftReference.class);
        ReportVersion draft = drafts.findById(result.reportVersionId()).orElseThrow(CasePreparationService::notFound);
        if (!sameScope(caseId, actor, draft.getCaseId(), draft.getTenantId(), draft.getEventId()))
            throw notFound();
        return CasePreparationDto.Draft.from(draft);
    }

    private CaseIdempotencyRecord replayRecord(UUID caseId, Authorized actor,
            String operation, Prepared prepared) {
        CaseIdempotencyRecord record = idempotency
                .findByTenantIdAndActorMembershipIdAndOperationAndTargetCaseIdAndKeyDigest(
                        actor.tenantId(), actor.memberId(), operation, caseId, prepared.keyDigest())
                .orElse(null);
        if (record != null && !record.getRequestDigest().equals(prepared.requestDigest()))
            throw conflict("IDEMPOTENCY_KEY_REUSED");
        return record;
    }

    private boolean sameScope(UUID caseId, Authorized actor, UUID storedCaseId,
            UUID storedTenantId, UUID storedEventId) {
        return caseId.equals(storedCaseId) && actor.tenantId().equals(storedTenantId)
                && actor.eventId().equals(storedEventId);
    }

    private static CaseApiException invalid() {
        return new CaseApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED");
    }

    private static CaseApiException notFound() {
        return new CaseApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }

    private static CaseApiException conflict(String code) {
        return new CaseApiException(HttpStatus.CONFLICT, code);
    }

    private static void page(int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE)
            throw new CaseApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
    }

    private record Authorized(UUID tenantId, UUID eventId, UUID memberId) { }
    private record Prepared(String keyDigest, String requestDigest) { }
    private record SnapshotReference(UUID evidenceSnapshotId) { }
    private record DraftReference(UUID reportVersionId) { }
}
