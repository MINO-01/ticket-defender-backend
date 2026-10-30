package com.ticket.defender_core.adapter.in.web.dto;

import com.ticket.defender_core.domain.ApprovalDecision;
import com.ticket.defender_core.domain.ApprovalRequest;
import com.ticket.defender_core.domain.Case;
import com.ticket.defender_core.domain.ReportVersion;
import com.ticket.defender_core.domain.ReviewReceipt;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

public final class ApprovalReviewDto {
    private ApprovalReviewDto() { }

    public record ReviewPackage(UUID approvalRequestId, ApprovalRequest.Status requestStatus,
                                UUID caseId, UUID eventId, long caseVersion,
                                UUID evidenceSnapshotId, long evidenceRevision,
                                String manifestSha256, String manifestJson,
                                UUID reportVersionId, long reportRevision, long reportVersion,
                                String reportContentSha256, String reportTemplateVersion,
                                String reportTemplateSha256, String reportContentJson,
                                String issuanceEligibility) {
        public static ReviewPackage from(ApprovalRequest request, Case target,
                com.ticket.defender_core.domain.EvidenceSnapshot snapshot, ReportVersion report) {
            return new ReviewPackage(request.getId(), request.getStatus(), target.getId(), target.getEventId(),
                    target.getCaseVersion(), snapshot.getId(), snapshot.getEvidenceRevision(),
                    snapshot.getManifestSha256(), snapshot.getManifestJson(), report.getId(),
                    report.getRevision(), report.getVersion(), report.getContentSha256(),
                    report.getTemplateVersion(), report.getTemplateSha256(), report.getContentJson(), "BLOCKED");
        }
    }

    public record Receipt(UUID reviewReceiptId, UUID approvalRequestId, ReviewReceipt.Status status,
                          String reviewReceiptToken, OffsetDateTime issuedAt, OffsetDateTime expiresAt) {
        public static Receipt from(ReviewReceipt receipt, String token) {
            return new Receipt(receipt.getId(), receipt.getApprovalRequestId(), receipt.getStatus(), token,
                    receipt.getIssuedAt().atOffset(ZoneOffset.UTC), receipt.getExpiresAt().atOffset(ZoneOffset.UTC));
        }
    }

    public record DecisionInput(ApprovalDecision.Outcome outcome, String reason,
                                UUID evidenceSnapshotId, String manifestSha256,
                                String reportContentSha256, String reviewReceiptToken,
                                Boolean reviewConfirmed, Long expectedCaseVersion,
                                Long expectedRequestVersion, Long expectedReportVersion) { }

    public record DecisionView(UUID approvalDecisionId, UUID approvalRequestId,
                               ApprovalDecision.Outcome outcome, ApprovalRequest.Status requestStatus,
                               Case.Status caseStatus, ReportVersion.Status reportStatus,
                               UUID reviewReceiptId, long caseVersion, long requestVersion,
                               long reportVersion, OffsetDateTime decidedAt,
                               String issuanceEligibility) {
        public static DecisionView from(ApprovalDecision decision, ApprovalRequest request,
                Case target, ReportVersion report) {
            return new DecisionView(decision.getId(), request.getId(), decision.getOutcome(), request.getStatus(),
                    target.getStatus(), ReportVersion.Status.valueOf(report.getStatus()),
                    decision.getReviewReceiptId(), target.getCaseVersion(), request.getVersion(), report.getVersion(),
                    decision.getDecidedAt().atOffset(ZoneOffset.UTC), "BLOCKED");
        }
    }

    public record WithdrawInput(String reason, Long expectedCaseVersion,
                                Long expectedRequestVersion, Long expectedReportVersion) { }

    public record WithdrawView(UUID approvalRequestId, ApprovalRequest.Status requestStatus,
                               Case.Status caseStatus, ReportVersion.Status reportStatus,
                               long caseVersion, long requestVersion, long reportVersion) {
        public static WithdrawView from(ApprovalRequest request, Case target, ReportVersion report) {
            return new WithdrawView(request.getId(), request.getStatus(), target.getStatus(),
                    ReportVersion.Status.valueOf(report.getStatus()), target.getCaseVersion(), request.getVersion(),
                    report.getVersion());
        }
    }
}
