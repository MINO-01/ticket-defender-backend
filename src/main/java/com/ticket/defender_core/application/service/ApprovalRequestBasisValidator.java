package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.persistence.CaseSourceReferenceRepository;
import com.ticket.defender_core.adapter.out.persistence.EvidenceAssetRepository;
import com.ticket.defender_core.adapter.out.persistence.EvidencePreservationPolicyRepository;
import com.ticket.defender_core.adapter.out.persistence.EvidencePreservationRecordRepository;
import com.ticket.defender_core.adapter.out.persistence.EvidenceVerificationRecordRepository;
import com.ticket.defender_core.domain.Case;
import com.ticket.defender_core.domain.CaseSourceReference;
import com.ticket.defender_core.domain.EvidenceAsset;
import com.ticket.defender_core.domain.EvidencePreservationPolicy;
import com.ticket.defender_core.domain.EvidencePreservationRecord;
import com.ticket.defender_core.domain.EvidenceSnapshot;
import com.ticket.defender_core.domain.EvidenceVerificationRecord;
import com.ticket.defender_core.domain.ReportVersion;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class ApprovalRequestBasisValidator {
    private final CaseSourceReferenceRepository sources;
    private final EvidenceAssetRepository assets;
    private final EvidenceVerificationRecordRepository verifications;
    private final EvidencePreservationRecordRepository preservations;
    private final EvidencePreservationPolicyRepository policies;
    private final CaseSnapshotManifest manifests;

    public void validate(Case target, EvidenceSnapshot snapshot, ReportVersion report,
            LocalDateTime now) {
        List<CaseSourceReference> references = sources.findByCaseIdOrderByLinkedAtAscIdAsc(target.getId());
        manifests.recheckStoredDatabase(target, references, snapshot, now);
        for (CaseSourceReference source : references) {
            if (source.getSourceType() != CaseSourceReference.Type.EVIDENCE_ASSET) continue;
            validateFile(target, snapshot, report, source, now);
        }
    }

    private void validateFile(Case target, EvidenceSnapshot snapshot, ReportVersion report,
            CaseSourceReference source, LocalDateTime now) {
        EvidenceAsset asset = assets.findById(source.getEvidenceId()).orElseThrow(
                ApprovalRequestBasisValidator::blocked);
        EvidenceVerificationRecord verification = verifications
                .findTopByEvidenceSnapshotIdAndSourceReferenceIdOrderByVerifiedAtDescIdDesc(
                        snapshot.getId(), source.getId()).orElseThrow(ApprovalRequestBasisValidator::blocked);
        if (verification.getVerificationStatus() != EvidenceVerificationRecord.Status.MATCH
                || !sameScope(target, snapshot, report, source, verification)
                || !asset.getEvidenceId().equals(verification.getEvidenceId())
                || !asset.getSha256().equals(verification.getExpectedSha256())
                || !asset.getSha256().equals(verification.getVerifiedSha256())
                || verification.getVerifiedByteSize() == null
                || asset.getByteSize() != verification.getVerifiedByteSize()
                || !snapshot.getManifestSha256().equals(verification.getManifestSha256())
                || !CaseSnapshotManifest.sha256(asset.getObjectKey())
                        .equals(verification.getObjectKeySha256())
                || !asset.getRetentionUntil().isAfter(now)) throw blocked();

        EvidencePreservationRecord preservation = preservations
                .findTopByEvidenceSnapshotIdAndSourceReferenceIdOrderByVerifiedAtDescIdDesc(
                        snapshot.getId(), source.getId()).orElseThrow(ApprovalRequestBasisValidator::blocked);
        if (preservation.getPreservationStatus() != EvidencePreservationRecord.Status.IMMUTABLE_VERIFIED
                || !sameScope(target, snapshot, source, preservation)
                || !asset.getEvidenceId().equals(preservation.getEvidenceId())
                || !verification.getId().equals(preservation.getEvidenceVerificationRecordId())
                || !asset.getSha256().equals(preservation.getObjectSha256())
                || asset.getByteSize() != preservation.getObjectSize()
                || preservation.getActualVersionId() == null
                || preservation.getActualVersionId().isBlank()
                || !preservation.getRetainUntil().isAfter(now)
                || preservation.getRetainUntil().isAfter(asset.getRetentionUntil())) throw blocked();

        EvidencePreservationPolicy policy = policies.findByPolicyIdAndPolicyVersion(
                preservation.getPreservationPolicyId(), preservation.getPreservationPolicyVersion())
                .orElseThrow(ApprovalRequestBasisValidator::blocked);
        EvidencePreservationPolicy latest = policies.findTopByPolicyIdOrderByPolicyVersionDesc(
                preservation.getPreservationPolicyId()).orElseThrow(ApprovalRequestBasisValidator::blocked);
        if (!policy.getId().equals(latest.getId())
                || policy.getPolicyStatus() != EvidencePreservationPolicy.Status.APPROVED
                || !target.getTenantId().equals(policy.getTenantId())
                || (policy.getEventId() != null && !target.getEventId().equals(policy.getEventId()))
                || policy.getApprovedAt() == null || policy.getRevokedAt() != null
                || policy.getEffectiveFrom().isAfter(now) || !policy.getEffectiveUntil().isAfter(now)
                || !Objects.equals(policy.getStorageProfile(), preservation.getStorageProfile())
                || policy.getPreservationMode() != preservation.getObjectLockMode()) throw blocked();
    }

    private static boolean sameScope(Case target, EvidenceSnapshot snapshot, ReportVersion report,
            CaseSourceReference source, EvidenceVerificationRecord verification) {
        return target.getTenantId().equals(verification.getTenantId())
                && target.getEventId().equals(verification.getEventId())
                && target.getId().equals(verification.getCaseId())
                && snapshot.getId().equals(verification.getEvidenceSnapshotId())
                && report.getEvidenceSnapshotId().equals(snapshot.getId())
                && source.getId().equals(verification.getSourceReferenceId());
    }

    private static boolean sameScope(Case target, EvidenceSnapshot snapshot,
            CaseSourceReference source, EvidencePreservationRecord preservation) {
        return target.getTenantId().equals(preservation.getTenantId())
                && target.getEventId().equals(preservation.getEventId())
                && target.getId().equals(preservation.getCaseId())
                && snapshot.getId().equals(preservation.getEvidenceSnapshotId())
                && source.getId().equals(preservation.getSourceReferenceId());
    }

    private static CaseApiException blocked() {
        return new CaseApiException(HttpStatus.UNPROCESSABLE_ENTITY, "APPROVAL_BASIS_BLOCKED");
    }
}
