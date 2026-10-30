package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.persistence.ApprovalDecisionRepository;
import com.ticket.defender_core.adapter.out.persistence.ApprovalRequestRepository;
import com.ticket.defender_core.adapter.out.persistence.CaseIdempotencyRepository;
import com.ticket.defender_core.adapter.out.persistence.CaseRepository;
import com.ticket.defender_core.adapter.out.persistence.EvidenceSnapshotRepository;
import com.ticket.defender_core.adapter.out.persistence.OfficialPdfArtifactRepository;
import com.ticket.defender_core.adapter.out.persistence.OfficialPdfAuditEventRepository;
import com.ticket.defender_core.adapter.out.persistence.OfficialPdfIssuanceAttemptRepository;
import com.ticket.defender_core.adapter.out.persistence.OfficialPdfIssuanceRepository;
import com.ticket.defender_core.adapter.out.persistence.ReportVersionRepository;
import com.ticket.defender_core.adapter.out.persistence.ReviewReceiptRepository;
import com.ticket.defender_core.domain.ApprovalDecision;
import com.ticket.defender_core.domain.ApprovalRequest;
import com.ticket.defender_core.domain.Case;
import com.ticket.defender_core.domain.CaseIdempotencyRecord;
import com.ticket.defender_core.domain.EvidenceSnapshot;
import com.ticket.defender_core.domain.OfficialPdfArtifact;
import com.ticket.defender_core.domain.OfficialPdfAuditEvent;
import com.ticket.defender_core.domain.OfficialPdfIssuance;
import com.ticket.defender_core.domain.OfficialPdfIssuanceAttempt;
import com.ticket.defender_core.domain.ReportVersion;
import com.ticket.defender_core.domain.ReviewReceipt;
import com.ticket.defender_core.security.AdminAccessService;
import com.ticket.defender_core.security.AdminResourceScope;
import com.ticket.defender_core.security.AdminRole;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Short transaction boundaries for reservation, object proof recording, and finalization only. */
@Service
@RequiredArgsConstructor
public class OfficialPdfIssuanceWriteService {
    public static final String ISSUE_OPERATION = "OFFICIAL_PDF_ISSUANCE";
    public static final String RETRY_OPERATION = "OFFICIAL_PDF_RETRY";
    private static final Set<AdminRole> TENANT_ADMIN = Set.of(AdminRole.TENANT_ADMIN);

    private final AdminAccessService access;
    private final CaseMembershipAccess memberships;
    private final CaseRepository cases;
    private final ApprovalDecisionRepository decisions;
    private final ApprovalRequestRepository requests;
    private final ReviewReceiptRepository receipts;
    private final EvidenceSnapshotRepository snapshots;
    private final ReportVersionRepository reports;
    private final OfficialPdfIssuanceRepository issuances;
    private final OfficialPdfIssuanceAttemptRepository attempts;
    private final OfficialPdfArtifactRepository artifacts;
    private final OfficialPdfAuditEventRepository audits;
    private final CaseIdempotencyRepository idempotency;
    private final OfficialPdfEligibilityValidator eligibility;
    private final OfficialPdfTemplateRegistry templates;
    private final OfficialPdfOperationGate operations;
    private final ObjectMapper json;
    /** A RUNNING attempt younger than this lease blocks retry; expiry never lets the old worker finalize. */
    @Value("${ticket-defender.official-pdf.attempt-lease:PT5M}")
    private Duration attemptLease;

    /** Lock order: scalar decision/issuance case ID, Case FOR UPDATE, then request/issuance FOR UPDATE. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Reservation reserveForDecision(UUID decisionId, UUID expectedMemberId, String keyDigest,
            String requestDigest, boolean retry) {
        operations.requireEnabled();
        UUID caseId = decisions.findCaseIdById(decisionId).orElseThrow(OfficialPdfIssuanceWriteService::notFound);
        Case target = cases.findByIdForUpdate(caseId).orElseThrow(OfficialPdfIssuanceWriteService::notFound);
        ApprovalDecision decision = decisions.findByIdForUpdate(decisionId)
                .orElseThrow(OfficialPdfIssuanceWriteService::notFound);
        requireDecisionScope(target, decision);
        UUID actor = tenantAdmin(target, expectedMemberId);
        Bound bound = bound(target, decision);

        OfficialPdfIssuance issuance = issuances.findByApprovalDecisionIdForUpdate(decisionId).orElse(null);
        if (issuance == null) {
            requireCurrent(eligibility.assess(bound.value(), templates.current().version(), templates.current().sha256(), now()));
            issuance = issuances.saveAndFlush(new OfficialPdfIssuance(decision, actor, templates.current(), now()));
            issuance.beginAttempt();
            issuances.saveAndFlush(issuance);
            OfficialPdfIssuanceAttempt attempt = attempts.saveAndFlush(new OfficialPdfIssuanceAttempt(issuance,
                    issuance.getAttemptCount(), OfficialPdfIssuanceAttempt.Kind.REQUEST, actor, now()));
            audits.saveAndFlush(new OfficialPdfAuditEvent(issuance, null, actor,
                    "ISSUANCE_RESERVED", "CURRENT", now()));
            saveIdempotency(target, actor, ISSUE_OPERATION, keyDigest, requestDigest, issuance, now());
            return new Reservation(issuance.getId(), true, issuance.hasCandidateObject(), attempt.getId());
        }
        requireIssuanceScope(target, issuance);
        String operation = retry ? RETRY_OPERATION : ISSUE_OPERATION;
        saveIdempotency(target, actor, operation, keyDigest, requestDigest, issuance, now());
        if (issuance.getStatus() == OfficialPdfIssuance.Status.GENERATED || !retry) {
            return new Reservation(issuance.getId(), false, issuance.hasCandidateObject(), null);
        }
        requireNoLiveAttempt(issuance, now());
        requireCurrent(eligibility.assess(bound.value(), issuance.getOfficialTemplateVersion(),
                issuance.getOfficialTemplateSha256(), now()));
        closeInterruptedAttempts(issuance, now());
        OfficialPdfIssuanceAttempt.Kind kind;
        if (issuance.hasCandidateObject()) {
            issuance.beginRecovery();
            kind = OfficialPdfIssuanceAttempt.Kind.RECOVERY;
        } else {
            issuance.beginAttempt();
            kind = OfficialPdfIssuanceAttempt.Kind.RETRY;
        }
        issuances.saveAndFlush(issuance);
        OfficialPdfIssuanceAttempt attempt = attempts.saveAndFlush(new OfficialPdfIssuanceAttempt(issuance,
                issuance.getAttemptCount(), kind, actor, now()));
        audits.saveAndFlush(new OfficialPdfAuditEvent(issuance, null, actor,
                "ISSUANCE_RETRIED", kind.name(), now()));
        return new Reservation(issuance.getId(), true, issuance.hasCandidateObject(), attempt.getId());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void recordObject(UUID issuanceId, UUID expectedMemberId, UUID attemptId,
            OfficialPdfStorage.ObjectProof proof) {
        UUID caseId = issuances.findCaseIdById(issuanceId).orElseThrow(OfficialPdfIssuanceWriteService::notFound);
        Case target = cases.findByIdForUpdate(caseId).orElseThrow(OfficialPdfIssuanceWriteService::notFound);
        OfficialPdfIssuance issuance = issuances.findByIdForUpdate(issuanceId)
                .orElseThrow(OfficialPdfIssuanceWriteService::notFound);
        requireIssuanceScope(target, issuance);
        UUID actor = tenantAdmin(target, expectedMemberId);
        requireCurrentAttempt(issuance, attemptId);
        if (proof == null) throw invalid("OBJECT_PROOF_INVALID");
        if (issuance.hasCandidateObject()) {
            if (!Objects.equals(issuance.getCandidateObjectVersion(), proof.objectVersion())
                    || !Objects.equals(issuance.getCandidatePdfSha256(), proof.sha256())
                    || !Objects.equals(issuance.getCandidatePdfSize(), proof.size()))
                throw conflict("OBJECT_PROOF_MISMATCH");
            return;
        }
        issuance.recordObject(proof.objectVersion(), proof.sha256(), proof.size());
        issuances.saveAndFlush(issuance);
        audits.saveAndFlush(new OfficialPdfAuditEvent(issuance, null, actor,
                "OBJECT_STORED", "OBJECT_VERIFIED", now()));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OfficialPdfArtifact finalizeIssuance(UUID issuanceId, UUID expectedMemberId, UUID attemptId,
            OfficialPdfStorage.ObjectProof verified) {
        operations.requireEnabled();
        UUID caseId = issuances.findCaseIdById(issuanceId).orElseThrow(OfficialPdfIssuanceWriteService::notFound);
        Case target = cases.findByIdForUpdate(caseId).orElseThrow(OfficialPdfIssuanceWriteService::notFound);
        OfficialPdfIssuance issuance = issuances.findByIdForUpdate(issuanceId)
                .orElseThrow(OfficialPdfIssuanceWriteService::notFound);
        requireIssuanceScope(target, issuance);
        UUID actor = tenantAdmin(target, expectedMemberId);
        OfficialPdfArtifact prior = artifacts.findByIssuanceId(issuanceId).orElse(null);
        if (prior != null) {
            if (!Objects.equals(issuance.getCandidateObjectKey(), prior.getObjectKey())
                    || !Objects.equals(issuance.getApprovalDecisionId(), prior.getApprovalDecisionId())
                    || !Objects.equals(issuance.getApprovalRequestId(), prior.getApprovalRequestId())
                    || !Objects.equals(issuance.getReviewReceiptId(), prior.getReviewReceiptId())
                    || !Objects.equals(issuance.getCandidateObjectVersion(), prior.getObjectVersion())
                    || !Objects.equals(issuance.getCandidatePdfSha256(), prior.getPdfSha256())
                    || !Objects.equals(issuance.getCandidatePdfSize(), prior.getPdfSize())) {
                throw conflict("ARTIFACT_BINDING_MISMATCH");
            }
            if (issuance.getStatus() != OfficialPdfIssuance.Status.GENERATED) {
                issuance.generatedFromArtifact(prior.getGeneratedAt());
                issuances.saveAndFlush(issuance);
            }
            return prior;
        }
        OfficialPdfIssuanceAttempt attempt = requireCurrentAttempt(issuance, attemptId);
        if (!issuance.hasCandidateObject() || verified == null
                || !Objects.equals(issuance.getCandidateObjectVersion(), verified.objectVersion())
                || !Objects.equals(issuance.getCandidatePdfSha256(), verified.sha256())
                || !Objects.equals(issuance.getCandidatePdfSize(), verified.size())) throw conflict("OBJECT_PROOF_MISMATCH");
        ApprovalDecision decision = decisions.findByIdForUpdate(issuance.getApprovalDecisionId())
                .orElseThrow(OfficialPdfIssuanceWriteService::notFound);
        requireDecisionScope(target, decision);
        Bound bound = bound(target, decision);
        requireIssuanceMatchesDecision(issuance, decision);
        requireCurrent(eligibility.assess(bound.value(), issuance.getOfficialTemplateVersion(),
                issuance.getOfficialTemplateSha256(), now()));
        OfficialPdfArtifact artifact = artifacts.saveAndFlush(new OfficialPdfArtifact(issuance, now()));
        issuance.generated(artifact.getGeneratedAt());
        issuances.saveAndFlush(issuance);
        completeAttempt(issuance, attempt, true, null, now());
        audits.saveAndFlush(new OfficialPdfAuditEvent(issuance, artifact.getId(), actor,
                "ISSUANCE_GENERATED", "CURRENT", now()));
        return artifact;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void markTerminal(UUID issuanceId, UUID expectedMemberId, UUID attemptId,
            OfficialPdfEligibilityValidator.Result result) {
        UUID caseId = issuances.findCaseIdById(issuanceId).orElseThrow(OfficialPdfIssuanceWriteService::notFound);
        Case target = cases.findByIdForUpdate(caseId).orElseThrow(OfficialPdfIssuanceWriteService::notFound);
        OfficialPdfIssuance issuance = issuances.findByIdForUpdate(issuanceId)
                .orElseThrow(OfficialPdfIssuanceWriteService::notFound);
        requireIssuanceScope(target, issuance);
        UUID actor = tenantAdmin(target, expectedMemberId);
        if (issuance.getStatus() == OfficialPdfIssuance.Status.GENERATED) return;
        // A superseded worker must not overwrite the state owned by the newer attempt.
        OfficialPdfIssuanceAttempt attempt = currentAttempt(issuance, attemptId);
        if (attempt == null) return;
        if (result != null && result.state() == OfficialPdfEligibilityValidator.State.STALE) {
            issuance.stale(result.code());
            completeAttempt(issuance, attempt, false, result.code(), now());
            audits.saveAndFlush(new OfficialPdfAuditEvent(issuance, null, actor,
                    "ISSUANCE_STALE", result.code(), now()));
        } else {
            String code = result == null ? "PDF_PROCESSING_FAILED" : result.code();
            issuance.failed(code);
            completeAttempt(issuance, attempt, false, code, now());
            audits.saveAndFlush(new OfficialPdfAuditEvent(issuance, null, actor,
                    "ISSUANCE_FAILED", code, now()));
        }
        issuances.saveAndFlush(issuance);
    }

    @Transactional(readOnly = true)
    public RenderSource renderSource(UUID issuanceId, UUID expectedMemberId) {
        OfficialPdfIssuance issuance = issuances.findById(issuanceId).orElseThrow(OfficialPdfIssuanceWriteService::notFound);
        Case target = cases.findById(issuance.getCaseId()).orElseThrow(OfficialPdfIssuanceWriteService::notFound);
        requireIssuanceScope(target, issuance);
        tenantAdmin(target, expectedMemberId);
        ApprovalDecision decision = decisions.findById(issuance.getApprovalDecisionId())
                .orElseThrow(OfficialPdfIssuanceWriteService::notFound);
        requireDecisionScope(target, decision);
        // Read-only snapshot for rendering: MySQL rejects FOR UPDATE inside READ ONLY transactions.
        // Finalization re-validates everything under Case and request locks.
        Bound bound = bound(target, decision, false);
        requireIssuanceMatchesDecision(issuance, decision);
        return new RenderSource(issuance, bound.value().report(),
                templates.pinned(issuance.getOfficialTemplateVersion(), issuance.getOfficialTemplateSha256()), decision,
                bound.value().request(), bound.value().snapshot(), sealedEvidenceSourceCount(bound.value().snapshot()));
    }

    @Transactional(readOnly = true)
    public OfficialPdfIssuance issuance(UUID issuanceId, UUID expectedMemberId) {
        OfficialPdfIssuance issuance = issuances.findById(issuanceId).orElseThrow(OfficialPdfIssuanceWriteService::notFound);
        Case target = cases.findById(issuance.getCaseId()).orElseThrow(OfficialPdfIssuanceWriteService::notFound);
        requireIssuanceScope(target, issuance);
        tenantAdmin(target, expectedMemberId);
        return issuance;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void auditDownload(UUID issuanceId, UUID artifactId, UUID expectedMemberId) {
        UUID caseId = issuances.findCaseIdById(issuanceId).orElseThrow(OfficialPdfIssuanceWriteService::notFound);
        Case target = cases.findByIdForUpdate(caseId).orElseThrow(OfficialPdfIssuanceWriteService::notFound);
        OfficialPdfIssuance issuance = issuances.findByIdForUpdate(issuanceId)
                .orElseThrow(OfficialPdfIssuanceWriteService::notFound);
        requireIssuanceScope(target, issuance);
        UUID actor = tenantAdmin(target, expectedMemberId);
        OfficialPdfArtifact artifact = artifacts.findById(artifactId).orElseThrow(OfficialPdfIssuanceWriteService::notFound);
        if (!issuance.getId().equals(artifact.getIssuanceId())
                || issuance.getStatus() != OfficialPdfIssuance.Status.GENERATED) throw conflict("OFFICIAL_PDF_NOT_GENERATED");
        audits.saveAndFlush(new OfficialPdfAuditEvent(issuance, artifact.getId(), actor,
                "ARTIFACT_DOWNLOADED", "OBJECT_VERIFIED", now()));
    }

    private Bound bound(Case target, ApprovalDecision decision) {
        return bound(target, decision, true);
    }

    private Bound bound(Case target, ApprovalDecision decision, boolean lockRequest) {
        ApprovalRequest request = (lockRequest ? requests.findByIdForUpdate(decision.getApprovalRequestId())
                : requests.findById(decision.getApprovalRequestId()))
                .orElseThrow(OfficialPdfIssuanceWriteService::notFound);
        ReviewReceipt receipt = receipts.findById(decision.getReviewReceiptId())
                .orElseThrow(OfficialPdfIssuanceWriteService::notFound);
        EvidenceSnapshot snapshot = snapshots.findById(decision.getEvidenceSnapshotId())
                .orElseThrow(OfficialPdfIssuanceWriteService::notFound);
        ReportVersion report = reports.findById(decision.getReportVersionId())
                .orElseThrow(OfficialPdfIssuanceWriteService::notFound);
        return new Bound(new OfficialPdfEligibilityValidator.Bound(target, decision, request, receipt, snapshot, report));
    }

    private UUID tenantAdmin(Case target, UUID expectedMemberId) {
        var actor = access.requireResourceAccess(new AdminResourceScope(target.getTenantId(), target.getEventId()),
                null, TENANT_ADMIN);
        UUID memberId = memberships.actor(actor).getId();
        if (!memberId.equals(expectedMemberId)) throw notFound();
        return memberId;
    }

    private void saveIdempotency(Case target, UUID actor, String operation, String keyDigest,
            String requestDigest, OfficialPdfIssuance issuance, LocalDateTime now) {
        CaseIdempotencyRecord prior = idempotency.findByTenantIdAndActorMembershipIdAndOperationAndTargetCaseIdAndKeyDigest(
                target.getTenantId(), actor, operation, target.getId(), keyDigest).orElse(null);
        if (prior != null) {
            if (!Objects.equals(prior.getRequestDigest(), requestDigest)) throw conflict("IDEMPOTENCY_KEY_REUSED");
            return;
        }
        idempotency.saveAndFlush(new CaseIdempotencyRecord(target.getTenantId(), actor, operation,
                target.getId(), keyDigest, requestDigest, target.getId(),
                json.writeValueAsString(Map.of("officialPdfIssuanceId", issuance.getId())), now));
    }

    private void closeInterruptedAttempts(OfficialPdfIssuance issuance, LocalDateTime now) {
        List<OfficialPdfIssuanceAttempt> running = attempts.findByIssuanceIdAndStatusForUpdate(
                issuance.getId(), OfficialPdfIssuanceAttempt.Status.RUNNING);
        for (OfficialPdfIssuanceAttempt interrupted : running) {
            interrupted.failed("RECOVERY_REPLACED", now);
            attempts.saveAndFlush(interrupted);
        }
    }

    private void completeAttempt(OfficialPdfIssuance issuance, OfficialPdfIssuanceAttempt attempt, boolean success,
            String code, LocalDateTime now) {
        if (success) attempt.succeeded(now);
        else if (issuance.getStatus() == OfficialPdfIssuance.Status.STALE) attempt.stale(code, now);
        else attempt.failed(code, now);
        attempts.saveAndFlush(attempt);
    }

    private void requireNoLiveAttempt(OfficialPdfIssuance issuance, LocalDateTime now) {
        LocalDateTime leaseStart = now.minus(attemptLease);
        if (attempts.findByIssuanceIdAndStatusForUpdate(issuance.getId(), OfficialPdfIssuanceAttempt.Status.RUNNING)
                .stream().anyMatch(running -> running.getStartedAt().isAfter(leaseStart)))
            throw conflict("OFFICIAL_PDF_ISSUANCE_IN_PROGRESS");
    }

    /** Fencing token: only the newest RUNNING attempt may record an object or finalize. */
    private OfficialPdfIssuanceAttempt requireCurrentAttempt(OfficialPdfIssuance issuance, UUID attemptId) {
        OfficialPdfIssuanceAttempt attempt = currentAttempt(issuance, attemptId);
        if (attempt == null) throw conflict("OFFICIAL_PDF_ATTEMPT_SUPERSEDED");
        return attempt;
    }

    private OfficialPdfIssuanceAttempt currentAttempt(OfficialPdfIssuance issuance, UUID attemptId) {
        if (attemptId == null) return null;
        return attempts.findByIssuanceIdAndStatusForUpdate(issuance.getId(), OfficialPdfIssuanceAttempt.Status.RUNNING)
                .stream().filter(running -> running.getId().equals(attemptId)
                        && running.getAttemptNumber() == issuance.getAttemptCount())
                .findFirst().orElse(null);
    }

    private static void requireCurrent(OfficialPdfEligibilityValidator.Result result) {
        if (result.state() == OfficialPdfEligibilityValidator.State.CURRENT) return;
        if (result.state() == OfficialPdfEligibilityValidator.State.STALE
                || "ISSUANCE_HOLD_ACTIVE".equals(result.code()) || "APPROVAL_NOT_CURRENT".equals(result.code()))
            throw conflict(result.code());
        throw new CaseApiException(HttpStatus.UNPROCESSABLE_ENTITY, result.code());
    }

    private static void requireDecisionScope(Case target, ApprovalDecision decision) {
        if (!target.getId().equals(decision.getCaseId()) || !target.getTenantId().equals(decision.getTenantId())
                || !target.getEventId().equals(decision.getEventId())) throw notFound();
    }

    private static void requireIssuanceScope(Case target, OfficialPdfIssuance issuance) {
        if (!target.getId().equals(issuance.getCaseId()) || !target.getTenantId().equals(issuance.getTenantId())
                || !target.getEventId().equals(issuance.getEventId())) throw notFound();
    }

    private static void requireIssuanceMatchesDecision(OfficialPdfIssuance issuance, ApprovalDecision decision) {
        if (!issuance.getApprovalDecisionId().equals(decision.getId())
                || !issuance.getApprovalRequestId().equals(decision.getApprovalRequestId())
                || !issuance.getReviewReceiptId().equals(decision.getReviewReceiptId())
                || !issuance.getReportVersionId().equals(decision.getReportVersionId())
                || issuance.getReportRevision() != decision.getReportRevision()
                || !issuance.getReportContentSha256().equals(decision.getReportContentSha256())
                || !issuance.getReportTemplateVersion().equals(decision.getReportTemplateVersion())
                || !issuance.getReportTemplateSha256().equals(decision.getReportTemplateSha256())
                || !issuance.getEvidenceSnapshotId().equals(decision.getEvidenceSnapshotId())
                || issuance.getEvidenceRevision() != decision.getEvidenceRevision()
                || !issuance.getManifestSha256().equals(decision.getManifestSha256())) throw conflict("APPROVAL_BINDING_STALE");
    }

    @SuppressWarnings("unchecked")
    private int sealedEvidenceSourceCount(EvidenceSnapshot snapshot) {
        try {
            Object decoded = json.readValue(snapshot.getManifestJson(), Map.class);
            if (!(decoded instanceof Map<?, ?> manifest) || !(manifest.get("sources") instanceof List<?> sources))
                throw conflict("APPROVAL_BASIS_STALE");
            return sources.size();
        } catch (CaseApiException known) {
            throw known;
        } catch (RuntimeException malformed) {
            throw conflict("APPROVAL_BASIS_STALE");
        }
    }

    private static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
    private static CaseApiException notFound() { return new CaseApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND"); }
    private static CaseApiException conflict(String code) { return new CaseApiException(HttpStatus.CONFLICT, code); }
    private static CaseApiException invalid(String code) { return new CaseApiException(HttpStatus.UNPROCESSABLE_ENTITY, code); }

    private record Bound(OfficialPdfEligibilityValidator.Bound value) { }
    public record Reservation(UUID issuanceId, boolean execute, boolean candidateStored, UUID attemptId) { }
    public record RenderSource(OfficialPdfIssuance issuance, ReportVersion report,
                               OfficialPdfTemplateRegistry.Template template, ApprovalDecision decision,
                               ApprovalRequest request, EvidenceSnapshot snapshot, int evidenceSourceCount) { }
}
