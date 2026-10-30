package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.OfficialPdfDto;
import com.ticket.defender_core.adapter.out.persistence.ApprovalDecisionRepository;
import com.ticket.defender_core.adapter.out.persistence.ApprovalRequestRepository;
import com.ticket.defender_core.adapter.out.persistence.CaseIdempotencyRepository;
import com.ticket.defender_core.adapter.out.persistence.CaseRepository;
import com.ticket.defender_core.adapter.out.persistence.EvidenceSnapshotRepository;
import com.ticket.defender_core.adapter.out.persistence.OfficialPdfArtifactRepository;
import com.ticket.defender_core.adapter.out.persistence.OfficialPdfIssuanceRepository;
import com.ticket.defender_core.adapter.out.persistence.ReportVersionRepository;
import com.ticket.defender_core.adapter.out.persistence.ReviewReceiptRepository;
import com.ticket.defender_core.domain.ApprovalDecision;
import com.ticket.defender_core.domain.ApprovalRequest;
import com.ticket.defender_core.domain.Case;
import com.ticket.defender_core.domain.CaseIdempotencyRecord;
import com.ticket.defender_core.domain.EvidenceSnapshot;
import com.ticket.defender_core.domain.OfficialPdfArtifact;
import com.ticket.defender_core.domain.OfficialPdfIssuance;
import com.ticket.defender_core.domain.ReportVersion;
import com.ticket.defender_core.domain.ReviewReceipt;
import com.ticket.defender_core.security.AdminAccessService;
import com.ticket.defender_core.security.AdminResourceScope;
import com.ticket.defender_core.security.AdminRole;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Orchestrates external rendering/storage outside the short write transactions. */
@Service
@RequiredArgsConstructor
public class OfficialPdfIssuanceService {
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile(
            "(?i)[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    private static final Set<AdminRole> TENANT_ADMIN = Set.of(AdminRole.TENANT_ADMIN);

    private final AdminAccessService access;
    private final CaseMembershipAccess memberships;
    private final ApprovalDecisionRepository decisions;
    private final ApprovalRequestRepository requests;
    private final ReviewReceiptRepository receipts;
    private final EvidenceSnapshotRepository snapshots;
    private final ReportVersionRepository reports;
    private final CaseRepository cases;
    private final OfficialPdfIssuanceRepository issuances;
    private final OfficialPdfArtifactRepository artifacts;
    private final CaseIdempotencyRepository idempotency;
    private final OfficialPdfEligibilityValidator eligibility;
    private final OfficialPdfEvidenceVerifier evidenceVerifier;
    private final OfficialPdfTemplateRegistry templates;
    private final OfficialPdfOperationGate operations;
    private final OfficialPdfIssuanceWriteService writer;
    private final OfficialPdfRenderer renderer;
    private final OfficialPdfStorage storage;
    private final ObjectMapper json;

    public OfficialPdfDto.Eligibility eligibility(UUID decisionId) {
        ApprovalDecision decision = decisions.findById(decisionId).orElseThrow(OfficialPdfIssuanceService::notFound);
        tenantAdmin(decision.getTenantId(), decision.getEventId());
        if (!operations.enabledForSyntheticIsolation()) {
            return new OfficialPdfDto.Eligibility(decisionId, decision.getReportVersionId(),
                    OfficialPdfEligibilityValidator.State.BLOCKED,
                    operationsCode());
        }
        OfficialPdfIssuance issuance = issuances.findByApprovalDecisionId(decisionId).orElse(null);
        String templateVersion = issuance == null ? templates.current().version()
                : issuance.getOfficialTemplateVersion();
        String templateSha = issuance == null ? templates.current().sha256()
                : issuance.getOfficialTemplateSha256();
        OfficialPdfEligibilityValidator.Result current = assess(decision, templateVersion, templateSha);
        return new OfficialPdfDto.Eligibility(decisionId, decision.getReportVersionId(), current.state(), current.code());
    }

    public OfficialPdfDto.Issuance request(UUID decisionId, String key) {
        operations.requireEnabled();
        ApprovalDecision decision = decisions.findById(decisionId).orElseThrow(OfficialPdfIssuanceService::notFound);
        Actor actor = tenantAdmin(decision.getTenantId(), decision.getEventId());
        Prepared prepared = prepare(key, decisionId);
        OfficialPdfIssuance replay = replay(decision.getCaseId(), actor, OfficialPdfIssuanceWriteService.ISSUE_OPERATION,
                prepared, decisionId);
        if (replay != null) return view(replay);
        verifyPhysicalEvidence(decision);
        try {
            OfficialPdfIssuanceWriteService.Reservation reservation = writer.reserveForDecision(decisionId,
                    actor.memberId(), prepared.keyDigest(), prepared.requestDigest(), false);
            if (!reservation.execute()) return view(writer.issuance(reservation.issuanceId(), actor.memberId()));
            return process(reservation, actor);
        } catch (DataIntegrityViolationException collision) {
            OfficialPdfIssuance committed = replay(decision.getCaseId(), actor,
                    OfficialPdfIssuanceWriteService.ISSUE_OPERATION, prepared, decisionId);
            if (committed != null) return view(committed);
            throw new CaseApiException(HttpStatus.CONFLICT, "OFFICIAL_PDF_ISSUANCE_CONFLICT");
        }
    }

    public OfficialPdfDto.Issuance retry(UUID issuanceId, String key) {
        operations.requireEnabled();
        OfficialPdfIssuance original = issuances.findById(issuanceId).orElseThrow(OfficialPdfIssuanceService::notFound);
        Actor actor = tenantAdmin(original.getTenantId(), original.getEventId());
        ApprovalDecision decision = decisions.findById(original.getApprovalDecisionId())
                .orElseThrow(OfficialPdfIssuanceService::notFound);
        Prepared prepared = prepare(key, issuanceId);
        OfficialPdfIssuance replay = replay(original.getCaseId(), actor, OfficialPdfIssuanceWriteService.RETRY_OPERATION,
                prepared, issuanceId);
        if (replay != null) return view(replay);
        verifyPhysicalEvidence(decision);
        try {
            OfficialPdfIssuanceWriteService.Reservation reservation = writer.reserveForDecision(
                    original.getApprovalDecisionId(), actor.memberId(), prepared.keyDigest(), prepared.requestDigest(), true);
            if (!reservation.execute()) return view(writer.issuance(reservation.issuanceId(), actor.memberId()));
            return process(reservation, actor);
        } catch (DataIntegrityViolationException collision) {
            OfficialPdfIssuance committed = replay(original.getCaseId(), actor,
                    OfficialPdfIssuanceWriteService.RETRY_OPERATION, prepared, issuanceId);
            if (committed != null) return view(committed);
            throw new CaseApiException(HttpStatus.CONFLICT, "OFFICIAL_PDF_ISSUANCE_CONFLICT");
        }
    }

    public OfficialPdfDto.Issuance detail(UUID issuanceId) {
        operations.requireEnabled();
        OfficialPdfIssuance issuance = issuances.findById(issuanceId).orElseThrow(OfficialPdfIssuanceService::notFound);
        tenantAdmin(issuance.getTenantId(), issuance.getEventId());
        return view(issuance);
    }

    public List<OfficialPdfDto.Issuance> history(UUID reportVersionId) {
        operations.requireEnabled();
        ReportVersion report = reports.findById(reportVersionId).orElseThrow(OfficialPdfIssuanceService::notFound);
        tenantAdmin(report.getTenantId(), report.getEventId());
        return issuances.findByReportVersionIdOrderByRequestedAtDesc(reportVersionId).stream().map(this::view).toList();
    }

    public Download download(UUID issuanceId) {
        operations.requireEnabled();
        OfficialPdfIssuance issuance = issuances.findById(issuanceId).orElseThrow(OfficialPdfIssuanceService::notFound);
        Actor actor = tenantAdmin(issuance.getTenantId(), issuance.getEventId());
        if (issuance.getStatus() != OfficialPdfIssuance.Status.GENERATED)
            throw new CaseApiException(HttpStatus.CONFLICT, "OFFICIAL_PDF_NOT_GENERATED");
        OfficialPdfArtifact artifact = artifacts.findByIssuanceId(issuanceId)
                .orElseThrow(OfficialPdfIssuanceService::notFound);
        try {
            OfficialPdfStorage.DownloadedPdf downloaded = storage.download(issuance.getId(), artifact.getObjectKey(),
                    artifact.getObjectVersion(), artifact.getPdfSha256(), artifact.getPdfSize());
            writer.auditDownload(issuanceId, artifact.getId(), actor.memberId());
            return new Download(downloaded.bytes(), artifact.getId(), artifact.getPdfSha256());
        } catch (CaseApiException known) {
            throw known;
        } catch (RuntimeException unavailable) {
            throw new CaseApiException(HttpStatus.SERVICE_UNAVAILABLE, "OFFICIAL_PDF_OBJECT_UNAVAILABLE");
        }
    }

    private OfficialPdfDto.Issuance process(OfficialPdfIssuanceWriteService.Reservation reservation, Actor actor) {
        OfficialPdfIssuance issuance = writer.issuance(reservation.issuanceId(), actor.memberId());
        UUID attemptId = reservation.attemptId();
        try {
            ApprovalDecision decision = decisions.findById(issuance.getApprovalDecisionId())
                    .orElseThrow(OfficialPdfIssuanceService::notFound);
            verifyPhysicalEvidence(decision);
            OfficialPdfStorage.ObjectProof proof;
            if (reservation.candidateStored()) {
                proof = storage.verify(issuance.getId(), issuance.getCandidateObjectKey(),
                        issuance.getCandidateObjectVersion(), issuance.getCandidatePdfSha256(), issuance.getCandidatePdfSize());
            } else {
                OfficialPdfIssuanceWriteService.RenderSource source = writer.renderSource(issuance.getId(), actor.memberId());
                byte[] bytes = renderer.render(new OfficialPdfRenderer.RenderContext(source.template(),
                        source.issuance(), source.report(), source.decision(), source.request(), source.snapshot(),
                        source.evidenceSourceCount()));
                proof = storage.putIfAbsent(issuance.getId(), issuance.getCandidateObjectKey(), bytes);
                writer.recordObject(issuance.getId(), actor.memberId(), attemptId, proof);
                issuance = writer.issuance(issuance.getId(), actor.memberId());
                proof = storage.verify(issuance.getId(), issuance.getCandidateObjectKey(),
                        issuance.getCandidateObjectVersion(), issuance.getCandidatePdfSha256(), issuance.getCandidatePdfSize());
            }
            verifyPhysicalEvidence(decision);
            writer.finalizeIssuance(issuance.getId(), actor.memberId(), attemptId, proof);
            return view(writer.issuance(issuance.getId(), actor.memberId()));
        } catch (CaseApiException eligibilityFailure) {
            mark(issuance.getId(), actor.memberId(), attemptId, resultFor(eligibilityFailure));
            return view(writer.issuance(issuance.getId(), actor.memberId()));
        } catch (RuntimeException processingFailure) {
            mark(issuance.getId(), actor.memberId(), attemptId, new OfficialPdfEligibilityValidator.Result(
                    OfficialPdfEligibilityValidator.State.BLOCKED, "PDF_PROCESSING_FAILED"));
            return view(writer.issuance(issuance.getId(), actor.memberId()));
        }
    }

    private void mark(UUID issuanceId, UUID memberId, UUID attemptId, OfficialPdfEligibilityValidator.Result result) {
        try {
            writer.markTerminal(issuanceId, memberId, attemptId, result);
        } catch (CaseApiException ignored) {
            // The original processing error remains authoritative; a role/scope revocation cannot turn it into success.
        }
    }

    private OfficialPdfDto.Issuance view(OfficialPdfIssuance issuance) {
        OfficialPdfArtifact artifact = artifacts.findByIssuanceId(issuance.getId()).orElse(null);
        ApprovalDecision decision = decisions.findById(issuance.getApprovalDecisionId()).orElse(null);
        OfficialPdfEligibilityValidator.Result current = decision == null
                ? new OfficialPdfEligibilityValidator.Result(OfficialPdfEligibilityValidator.State.BLOCKED,
                "APPROVAL_NOT_CURRENT")
                : assess(decision, issuance.getOfficialTemplateVersion(), issuance.getOfficialTemplateSha256());
        return OfficialPdfDto.Issuance.from(issuance, artifact, current);
    }

    private OfficialPdfEligibilityValidator.Result assess(ApprovalDecision decision, String templateVersion,
            String templateSha256) {
        Case target = cases.findById(decision.getCaseId()).orElse(null);
        ApprovalRequest request = requests.findById(decision.getApprovalRequestId()).orElse(null);
        ReviewReceipt receipt = receipts.findById(decision.getReviewReceiptId()).orElse(null);
        EvidenceSnapshot snapshot = snapshots.findById(decision.getEvidenceSnapshotId()).orElse(null);
        ReportVersion report = reports.findById(decision.getReportVersionId()).orElse(null);
        if (target == null || request == null || receipt == null || snapshot == null || report == null)
            return new OfficialPdfEligibilityValidator.Result(OfficialPdfEligibilityValidator.State.STALE,
                    "APPROVAL_BINDING_STALE");
        OfficialPdfEligibilityValidator.Bound bound = new OfficialPdfEligibilityValidator.Bound(target, decision,
                request, receipt, snapshot, report);
        OfficialPdfEligibilityValidator.Result result = eligibility.assess(bound, templateVersion, templateSha256,
                LocalDateTime.now(ZoneOffset.UTC));
        if (result.state() != OfficialPdfEligibilityValidator.State.CURRENT) return result;
        try {
            evidenceVerifier.verify(bound, LocalDateTime.now(ZoneOffset.UTC));
            return result;
        } catch (CaseApiException physicalFailure) {
            return resultFor(physicalFailure);
        }
    }

    private void verifyPhysicalEvidence(ApprovalDecision decision) {
        Case target = cases.findById(decision.getCaseId()).orElseThrow(OfficialPdfIssuanceService::notFound);
        ApprovalRequest request = requests.findById(decision.getApprovalRequestId())
                .orElseThrow(OfficialPdfIssuanceService::notFound);
        ReviewReceipt receipt = receipts.findById(decision.getReviewReceiptId())
                .orElseThrow(OfficialPdfIssuanceService::notFound);
        EvidenceSnapshot snapshot = snapshots.findById(decision.getEvidenceSnapshotId())
                .orElseThrow(OfficialPdfIssuanceService::notFound);
        ReportVersion report = reports.findById(decision.getReportVersionId())
                .orElseThrow(OfficialPdfIssuanceService::notFound);
        evidenceVerifier.verify(new OfficialPdfEligibilityValidator.Bound(target, decision, request, receipt,
                snapshot, report), LocalDateTime.now(ZoneOffset.UTC));
    }

    private OfficialPdfIssuance replay(UUID caseId, Actor actor, String operation, Prepared prepared,
            UUID expectedTarget) {
        CaseIdempotencyRecord record = idempotency.findByTenantIdAndActorMembershipIdAndOperationAndTargetCaseIdAndKeyDigest(
                actor.tenantId(), actor.memberId(), operation, caseId, prepared.keyDigest()).orElse(null);
        if (record == null) return null;
        if (!record.getRequestDigest().equals(prepared.requestDigest()))
            throw new CaseApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED");
        UUID issuanceId = json.readValue(record.getResponseJson(), IssuanceReference.class).officialPdfIssuanceId();
        OfficialPdfIssuance issuance = issuances.findById(issuanceId).orElseThrow(OfficialPdfIssuanceService::notFound);
        if (!expectedTarget.equals(operation.equals(OfficialPdfIssuanceWriteService.ISSUE_OPERATION)
                ? issuance.getApprovalDecisionId() : issuance.getId())
                || !caseId.equals(issuance.getCaseId()) || !actor.tenantId().equals(issuance.getTenantId())) throw notFound();
        return issuance;
    }

    private Actor tenantAdmin(UUID tenantId, UUID eventId) {
        var actor = access.requireResourceAccess(new AdminResourceScope(tenantId, eventId), null, TENANT_ADMIN);
        return new Actor(actor.tenantId(), actor.eventId(), memberships.actor(actor).getId());
    }

    private static Prepared prepare(String key, UUID target) {
        if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches())
            throw new CaseApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY");
        return new Prepared(CaseSnapshotManifest.sha256(key.toLowerCase()),
                CaseSnapshotManifest.sha256("official-pdf:" + target));
    }

    private String operationsCode() {
        try {
            operations.requireEnabled();
            return "CURRENT";
        } catch (CaseApiException error) {
            return error.code();
        }
    }

    private static OfficialPdfEligibilityValidator.Result resultFor(CaseApiException error) {
        boolean stale = error.code().contains("STALE") || "SOURCE_CHANGED".equals(error.code())
                || "OFFICIAL_TEMPLATE_CHANGED".equals(error.code());
        return new OfficialPdfEligibilityValidator.Result(stale ? OfficialPdfEligibilityValidator.State.STALE
                : OfficialPdfEligibilityValidator.State.BLOCKED, error.code());
    }

    private static CaseApiException notFound() { return new CaseApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND"); }

    private record Actor(UUID tenantId, UUID eventId, UUID memberId) { }
    private record Prepared(String keyDigest, String requestDigest) { }
    private record IssuanceReference(UUID officialPdfIssuanceId) { }
    public record Download(byte[] bytes, UUID artifactId, String sha256) { }
}
