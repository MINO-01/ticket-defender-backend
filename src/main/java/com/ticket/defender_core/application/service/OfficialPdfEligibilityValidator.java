package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.persistence.AdminMembershipRepository;
import com.ticket.defender_core.adapter.out.persistence.IssuanceHoldRepository;
import com.ticket.defender_core.domain.AdminMembershipEntity;
import com.ticket.defender_core.domain.ApprovalDecision;
import com.ticket.defender_core.domain.ApprovalRequest;
import com.ticket.defender_core.domain.Case;
import com.ticket.defender_core.domain.EvidenceSnapshot;
import com.ticket.defender_core.domain.IssuanceHold;
import com.ticket.defender_core.domain.ReportVersion;
import com.ticket.defender_core.domain.ReviewReceipt;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Objects;

/** Computes present issuance eligibility without changing the immutable human decision. */
@Component
@RequiredArgsConstructor
public class OfficialPdfEligibilityValidator {
    public enum State { CURRENT, STALE, BLOCKED }
    public record Result(State state, String code) {
        public static Result current() { return new Result(State.CURRENT, "CURRENT"); }
    }
    public record Bound(Case target, ApprovalDecision decision, ApprovalRequest request,
                        ReviewReceipt receipt, EvidenceSnapshot snapshot, ReportVersion report) { }

    private final ApprovalRequestBasisValidator basis;
    private final ReportTemplateRegistry reportTemplates;
    private final OfficialPdfTemplateRegistry officialTemplates;
    private final IssuanceHoldRepository holds;
    private final AdminMembershipRepository memberships;

    public Result assess(Bound bound, String officialTemplateVersion, String officialTemplateSha256,
            LocalDateTime now) {
        if (!sameScope(bound) || !decisionBinds(bound)) return stale("APPROVAL_BINDING_STALE");
        if (bound.decision().getOutcome() != ApprovalDecision.Outcome.APPROVE
                || bound.request().getStatus() != ApprovalRequest.Status.APPROVED
                || bound.receipt().getStatus() != ReviewReceipt.Status.CONSUMED
                || !Case.Status.APPROVED_FOR_REPORT.equals(bound.target().getStatus())
                || !Objects.equals(bound.target().getCurrentReportVersionId(), bound.report().getId())
                || !ReportVersion.Status.APPROVED.name().equals(bound.report().getStatus())) {
            return blocked("APPROVAL_NOT_CURRENT");
        }
        if (bound.target().getEvidenceRevision() != bound.decision().getEvidenceRevision()
                || bound.snapshot().getEvidenceRevision() != bound.decision().getEvidenceRevision()
                || bound.report().getEvidenceRevision() != bound.decision().getEvidenceRevision()
                || bound.report().getRevision() != bound.decision().getReportRevision()
                || !bound.snapshot().getManifestSha256().equals(bound.decision().getManifestSha256())
                || !CaseSnapshotManifest.sha256(bound.snapshot().getManifestJson())
                        .equals(bound.snapshot().getManifestSha256())
                || !CaseSnapshotManifest.sha256(bound.report().getContentJson())
                        .equals(bound.report().getContentSha256())) return stale("APPROVAL_BASIS_STALE");
        if (!reportTemplates.verifies(bound.report().getTemplateVersion(), bound.report().getTemplateSha256()))
            return blocked("REPORT_TEMPLATE_NOT_RETAINED");
        if (!officialTemplates.verifies(officialTemplateVersion, officialTemplateSha256))
            return stale("OFFICIAL_TEMPLATE_CHANGED");
        if (!independentApprover(bound)) return blocked("APPROVER_INDEPENDENCE_INVALID");
        if (holds.existsByTenantIdAndEventIdAndCaseIdAndStatus(bound.target().getTenantId(),
                bound.target().getEventId(), bound.target().getId(), IssuanceHold.Status.ACTIVE))
            return blocked("ISSUANCE_HOLD_ACTIVE");
        try {
            basis.validate(bound.target(), bound.snapshot(), bound.report(), now);
            return Result.current();
        } catch (CaseApiException failure) {
            if ("SOURCE_CHANGED".equals(failure.code()) || "EVIDENCE_VERSION_MISMATCH".equals(failure.code())
                    || "APPROVAL_REVIEW_MISMATCH".equals(failure.code())
                    || "REPORT_CONTENT_MISMATCH".equals(failure.code())) return stale("APPROVAL_BASIS_STALE");
            return blocked(failure.code());
        }
    }

    private boolean independentApprover(Bound bound) {
        AdminMembershipEntity requester = memberships.findById(bound.request().getRequestedByMembershipId()).orElse(null);
        AdminMembershipEntity approver = memberships.findById(bound.decision().getDecidedByMembershipId()).orElse(null);
        return requester != null && approver != null
                && bound.target().getTenantId().equals(requester.getTenantId())
                && bound.target().getTenantId().equals(approver.getTenantId())
                && bound.decision().getDecidedByMembershipId().equals(bound.receipt().getApproverMembershipId())
                && !Objects.equals(requester.getSubject(), approver.getSubject());
    }

    private static boolean sameScope(Bound b) {
        return b.target() != null && b.decision() != null && b.request() != null && b.receipt() != null
                && b.snapshot() != null && b.report() != null
                && b.target().getTenantId().equals(b.decision().getTenantId())
                && b.target().getEventId().equals(b.decision().getEventId())
                && b.target().getId().equals(b.decision().getCaseId())
                && b.target().getTenantId().equals(b.request().getTenantId())
                && b.target().getEventId().equals(b.request().getEventId())
                && b.target().getId().equals(b.request().getCaseId())
                && b.target().getTenantId().equals(b.receipt().getTenantId())
                && b.target().getEventId().equals(b.receipt().getEventId())
                && b.target().getId().equals(b.receipt().getCaseId())
                && b.target().getTenantId().equals(b.snapshot().getTenantId())
                && b.target().getEventId().equals(b.snapshot().getEventId())
                && b.target().getId().equals(b.snapshot().getCaseId())
                && b.target().getTenantId().equals(b.report().getTenantId())
                && b.target().getEventId().equals(b.report().getEventId())
                && b.target().getId().equals(b.report().getCaseId());
    }

    private static boolean decisionBinds(Bound b) {
        ApprovalDecision d = b.decision();
        ApprovalRequest r = b.request();
        ReviewReceipt receipt = b.receipt();
        ReportVersion report = b.report();
        EvidenceSnapshot snapshot = b.snapshot();
        return d.getApprovalRequestId().equals(r.getId()) && d.getReviewReceiptId().equals(receipt.getId())
                && d.getReportVersionId().equals(report.getId()) && d.getReportRevision() == report.getRevision()
                && d.getReportContentSha256().equals(report.getContentSha256())
                && d.getReportTemplateVersion().equals(report.getTemplateVersion())
                && d.getReportTemplateSha256().equals(report.getTemplateSha256())
                && d.getEvidenceSnapshotId().equals(snapshot.getId())
                && d.getEvidenceRevision() == snapshot.getEvidenceRevision()
                && d.getManifestSha256().equals(snapshot.getManifestSha256())
                && r.getId().equals(receipt.getApprovalRequestId())
                && r.getReportVersionId().equals(receipt.getReportVersionId())
                && r.getReportRevision() == receipt.getReportRevision()
                && r.getEvidenceSnapshotId().equals(receipt.getEvidenceSnapshotId())
                && r.getEvidenceRevision() == receipt.getEvidenceRevision()
                && r.getReportContentSha256().equals(receipt.getReportContentSha256())
                && r.getReportTemplateVersion().equals(receipt.getReportTemplateVersion())
                && r.getReportTemplateSha256().equals(receipt.getReportTemplateSha256())
                && r.getManifestSha256().equals(receipt.getManifestSha256())
                && d.getReportRevision() == receipt.getReportRevision()
                && d.getReportContentSha256().equals(receipt.getReportContentSha256())
                && d.getReportTemplateVersion().equals(receipt.getReportTemplateVersion())
                && d.getReportTemplateSha256().equals(receipt.getReportTemplateSha256())
                && d.getEvidenceRevision() == receipt.getEvidenceRevision()
                && d.getManifestSha256().equals(receipt.getManifestSha256());
    }

    private static Result stale(String code) { return new Result(State.STALE, code); }
    private static Result blocked(String code) { return new Result(State.BLOCKED, code); }
}
