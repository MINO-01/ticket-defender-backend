package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.persistence.EvidenceVerificationRecordRepository;
import com.ticket.defender_core.domain.EvidenceVerificationRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class EvidencePreservationContext {
    private final EvidenceIntegrityContext integrity;
    private final EvidenceVerificationRecordRepository verifications;

    public Checked load(UUID caseId, Long expectedCaseVersion, UUID snapshotId, UUID sourceId,
            UUID evidenceId, UUID verificationId, UUID expectedActor) {
        EvidenceIntegrityContext.Checked source = integrity.load(caseId, snapshotId, sourceId,
                evidenceId, expectedCaseVersion, expectedActor);
        EvidenceVerificationRecord verification = verifications.findById(verificationId)
                .orElseThrow(EvidencePreservationContext::notFound);
        if (!caseId.equals(verification.getCaseId())
                || !source.target().getTenantId().equals(verification.getTenantId())
                || !source.target().getEventId().equals(verification.getEventId())
                || !snapshotId.equals(verification.getEvidenceSnapshotId())
                || !sourceId.equals(verification.getSourceReferenceId())
                || !evidenceId.equals(verification.getEvidenceId())
                || !source.asset().getFanReportId().equals(verification.getFanReportId())) throw notFound();
        EvidenceVerificationRecord latest = verifications
                .findTopByEvidenceSnapshotIdAndSourceReferenceIdOrderByVerifiedAtDescIdDesc(snapshotId, sourceId)
                .orElseThrow(EvidencePreservationContext::notFound);
        if (!verification.getId().equals(latest.getId()))
            throw new CaseApiException(HttpStatus.CONFLICT, "VERIFICATION_STALE");
        if (verification.getVerificationStatus() != EvidenceVerificationRecord.Status.MATCH
                || verification.getVerifiedSha256() == null || verification.getVerifiedByteSize() == null)
            throw new CaseApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VERIFICATION_NOT_MATCHED");
        if (!Objects.equals(source.snapshot().getManifestSha256(), verification.getManifestSha256())
                || !Objects.equals(source.asset().getSha256(), verification.getExpectedSha256())
                || !Objects.equals(source.asset().getSha256(), verification.getVerifiedSha256())
                || source.asset().getByteSize() != verification.getExpectedByteSize()
                || source.asset().getByteSize() != verification.getVerifiedByteSize()
                || !CaseSnapshotManifest.sha256(source.asset().getObjectKey())
                .equals(verification.getObjectKeySha256()))
            throw new CaseApiException(HttpStatus.CONFLICT, "VERIFICATION_STALE");
        return new Checked(source, verification);
    }

    public record Checked(EvidenceIntegrityContext.Checked source,
                          EvidenceVerificationRecord verification) { }

    private static CaseApiException notFound() {
        return new CaseApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }
}
