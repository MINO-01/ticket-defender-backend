package com.ticket.defender_core.adapter.in.web.dto;

import com.ticket.defender_core.domain.EvidenceVerificationRecord;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

public final class EvidenceIntegrityDto {
    private EvidenceIntegrityDto() { }

    public record VerifyInput(Long expectedCaseVersion, UUID evidenceSnapshotId,
                              UUID sourceReferenceId, UUID evidenceId, String reason) { }

    public record Verification(UUID recordId, UUID caseId, UUID evidenceSnapshotId,
                               UUID sourceReferenceId, UUID evidenceId,
                               EvidenceVerificationRecord.Status verificationStatus,
                               EvidenceVerificationRecord.Preservation preservationStatus,
                               EvidenceVerificationRecord.MarkerKind markerKind,
                               EvidenceVerificationRecord.Method verificationMethod,
                               Long verifiedByteSize, OffsetDateTime verifiedAt) {
        public static Verification from(EvidenceVerificationRecord record) {
            return new Verification(record.getId(), record.getCaseId(), record.getEvidenceSnapshotId(),
                    record.getSourceReferenceId(), record.getEvidenceId(), record.getVerificationStatus(),
                    record.getPreservationStatus(), record.getMarkerKind(), record.getVerificationMethod(),
                    record.getVerifiedByteSize(), record.getVerifiedAt().atOffset(ZoneOffset.UTC));
        }
    }

    public record VerificationPage(List<Verification> items, int page, int pageSize, boolean hasNext) { }

    public record Evaluation(UUID caseId, UUID evidenceSnapshotId, UUID reportVersionId,
                             String technicalIntegrity, String approvalEligibility,
                             List<String> reasons) { }
}
