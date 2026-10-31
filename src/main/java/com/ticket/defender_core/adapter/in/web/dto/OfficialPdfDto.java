package com.ticket.defender_core.adapter.in.web.dto;

import com.ticket.defender_core.application.service.OfficialPdfEligibilityValidator;
import com.ticket.defender_core.domain.OfficialPdfArtifact;
import com.ticket.defender_core.domain.OfficialPdfIssuance;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** Deliberately omits private object keys, credentials, and any receipt token material. */
public final class OfficialPdfDto {
    private OfficialPdfDto() { }

    public record Eligibility(UUID approvalDecisionId, UUID reportVersionId,
                              OfficialPdfEligibilityValidator.State state, String code) { }

    public record Issuance(UUID officialPdfIssuanceId, UUID approvalDecisionId, UUID approvalRequestId,
                           UUID reportVersionId, long reportRevision,
                           OfficialPdfIssuance.Status status,
                           OfficialPdfEligibilityValidator.State eligibility,
                           String eligibilityCode, String officialTemplateVersion,
                           String officialTemplateSha256, String pdfSha256, Long pdfSize,
                           OffsetDateTime requestedAt, OffsetDateTime completedAt,
                           String lastFailureCode, UUID artifactId) {
        public static Issuance from(OfficialPdfIssuance issuance, OfficialPdfArtifact artifact,
                OfficialPdfEligibilityValidator.Result eligibility) {
            return new Issuance(issuance.getId(), issuance.getApprovalDecisionId(),
                    issuance.getApprovalRequestId(), issuance.getReportVersionId(), issuance.getReportRevision(),
                    issuance.getStatus(), eligibility.state(), eligibility.code(), issuance.getOfficialTemplateVersion(),
                    issuance.getOfficialTemplateSha256(), artifact == null ? null : artifact.getPdfSha256(),
                    artifact == null ? null : artifact.getPdfSize(),
                    issuance.getRequestedAt().atOffset(ZoneOffset.UTC), issuance.getCompletedAt() == null ? null
                    : issuance.getCompletedAt().atOffset(ZoneOffset.UTC), issuance.getLastFailureCode(),
                    artifact == null ? null : artifact.getId());
        }
    }

    public record Artifact(UUID officialPdfArtifactId, UUID officialPdfIssuanceId, UUID approvalDecisionId,
                           UUID reportVersionId, long reportRevision, String pdfSha256, long pdfSize,
                           String officialTemplateVersion, String officialTemplateSha256,
                           OffsetDateTime generatedAt) {
        public static Artifact from(OfficialPdfArtifact value) {
            return new Artifact(value.getId(), value.getIssuanceId(), value.getApprovalDecisionId(),
                    value.getReportVersionId(), value.getReportRevision(), value.getPdfSha256(), value.getPdfSize(),
                    value.getOfficialTemplateVersion(), value.getOfficialTemplateSha256(),
                    value.getGeneratedAt().atOffset(ZoneOffset.UTC));
        }
    }
}
