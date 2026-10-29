package com.ticket.defender_core.adapter.in.web.dto;

import com.ticket.defender_core.domain.ApprovalRequest;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

public final class ApprovalRequestDto {
    private ApprovalRequestDto() { }

    public record Submit(UUID reportVersionId, UUID evidenceSnapshotId,
                         Long expectedCaseVersion, Long expectedReportVersion,
                         String expectedReportContentSha256, String reason) { }

    public record View(UUID approvalRequestId, UUID caseId, UUID eventId,
                       UUID reportVersionId, long reportRevision, String reportContentSha256,
                       String reportTemplateVersion, String reportTemplateSha256,
                       UUID evidenceSnapshotId, long evidenceRevision, String manifestSha256,
                       UUID requestedBy, OffsetDateTime requestedAt, String reason,
                       ApprovalRequest.Status status, long caseVersionAtSubmit,
                       long version, String approvalEligibility) {
        public static View from(ApprovalRequest request) {
            return new View(request.getId(), request.getCaseId(), request.getEventId(),
                    request.getReportVersionId(), request.getReportRevision(),
                    request.getReportContentSha256(), request.getReportTemplateVersion(),
                    request.getReportTemplateSha256(), request.getEvidenceSnapshotId(),
                    request.getEvidenceRevision(), request.getManifestSha256(),
                    request.getRequestedByMembershipId(),
                    request.getRequestedAt().atOffset(ZoneOffset.UTC), request.getReason(),
                    request.getStatus(), request.getCaseVersionAtSubmit(), request.getVersion(),
                    "BLOCKED");
        }
    }
}
