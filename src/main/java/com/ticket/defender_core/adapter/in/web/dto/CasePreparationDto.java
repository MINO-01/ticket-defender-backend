package com.ticket.defender_core.adapter.in.web.dto;

import com.ticket.defender_core.domain.EvidenceSnapshot;
import com.ticket.defender_core.domain.ReportVersion;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

public final class CasePreparationDto {
    private CasePreparationDto() { }

    public record Capture(Long expectedCaseVersion, String reason) { }

    public record SnapshotPage(List<Snapshot> items, int page, int pageSize, boolean hasNext) { }
    public record DraftPage(List<Draft> items, int page, int pageSize, boolean hasNext) { }

    public record Snapshot(UUID evidenceSnapshotId, UUID caseId, UUID eventId,
                           long evidenceRevision, long caseVersionAtCapture, int manifestSchemaVersion,
                           String manifestSha256, String manifestJson, String sourceIntegrity,
                           boolean approvalEligible, UUID createdByMembershipId, OffsetDateTime capturedAt) {
        public static Snapshot from(EvidenceSnapshot value) {
            return new Snapshot(value.getId(), value.getCaseId(), value.getEventId(),
                    value.getEvidenceRevision(), value.getCaseVersionAtCapture(),
                    value.getManifestSchemaVersion(), value.getManifestSha256(), value.getManifestJson(),
                    value.getSourceIntegrity(), false, value.getCreatedByMembershipId(),
                    value.getCapturedAt().atOffset(ZoneOffset.UTC));
        }
    }

    public record DraftInput(Long expectedCaseVersion, UUID evidenceSnapshotId,
                             String title, String assessment, String limitations,
                             String conclusion, String reason) { }

    public record Draft(UUID reportVersionId, UUID caseId, UUID eventId,
                        UUID evidenceSnapshotId, long evidenceRevision, long revision, long version,
                        String templateVersion,
                        String contentSha256, String contentJson, ReportVersion.Status status,
                        boolean renderable, boolean approvalEligible,
                        UUID createdByMembershipId, OffsetDateTime createdAt) {
        public static Draft from(ReportVersion value) {
            return new Draft(value.getId(), value.getCaseId(), value.getEventId(),
                    value.getEvidenceSnapshotId(), value.getEvidenceRevision(), value.getRevision(),
                    value.getVersion(), value.getTemplateVersion(),
                    value.getContentSha256(), value.getContentJson(), ReportVersion.Status.DRAFT,
                    false, false, value.getCreatedByMembershipId(),
                    value.getCreatedAt().atOffset(ZoneOffset.UTC));
        }
    }
}
