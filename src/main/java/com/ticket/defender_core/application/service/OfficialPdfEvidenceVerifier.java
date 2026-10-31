package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.persistence.CaseSourceReferenceRepository;
import com.ticket.defender_core.adapter.out.persistence.EvidenceAssetRepository;
import com.ticket.defender_core.adapter.out.persistence.EvidencePreservationRecordRepository;
import com.ticket.defender_core.adapter.out.persistence.EvidenceVerificationRecordRepository;
import com.ticket.defender_core.adapter.out.storage.S3EvidenceObjectStorage;
import com.ticket.defender_core.domain.CaseSourceReference;
import com.ticket.defender_core.domain.EvidenceAsset;
import com.ticket.defender_core.domain.EvidencePreservationRecord;
import com.ticket.defender_core.domain.EvidenceVerificationRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Rechecks the private W06/W07 objects immediately around issuance work.  It deliberately runs
 * outside the short issuance write transactions; the write service still rechecks the database
 * bindings while holding the Case lock before an artifact is finalized.
 */
@Component
@RequiredArgsConstructor
public class OfficialPdfEvidenceVerifier {
    private final CaseSourceReferenceRepository sources;
    private final EvidenceAssetRepository assets;
    private final EvidenceVerificationRecordRepository verifications;
    private final EvidencePreservationRecordRepository preservations;
    private final EvidenceObjectStorage evidenceStorage;
    private final EvidencePreservationStorage preservationStorage;

    @Value("${ticket-defender.evidence-preservation.max-bytes:20971520}")
    private long maximumBytes;

    public void verify(OfficialPdfEligibilityValidator.Bound bound, LocalDateTime now) {
        if (maximumBytes < 1) throw unavailable();
        for (CaseSourceReference source : sources.findByCaseIdOrderByLinkedAtAscIdAsc(bound.target().getId())) {
            if (source.getSourceType() != CaseSourceReference.Type.EVIDENCE_ASSET) continue;
            verifyFile(bound, source, now);
        }
    }

    private void verifyFile(OfficialPdfEligibilityValidator.Bound bound, CaseSourceReference source,
            LocalDateTime now) {
        EvidenceAsset asset = assets.findById(source.getEvidenceId()).orElseThrow(OfficialPdfEvidenceVerifier::stale);
        EvidenceVerificationRecord verification = verifications
                .findTopByEvidenceSnapshotIdAndSourceReferenceIdOrderByVerifiedAtDescIdDesc(
                        bound.snapshot().getId(), source.getId()).orElseThrow(OfficialPdfEvidenceVerifier::stale);
        EvidencePreservationRecord preservation = preservations
                .findTopByEvidenceSnapshotIdAndSourceReferenceIdOrderByVerifiedAtDescIdDesc(
                        bound.snapshot().getId(), source.getId()).orElseThrow(OfficialPdfEvidenceVerifier::blocked);
        if (!bound.target().getId().equals(source.getCaseId())
                || !asset.getEvidenceId().equals(source.getEvidenceId())
                || verification.getVerificationStatus() != EvidenceVerificationRecord.Status.MATCH
                || !asset.getEvidenceId().equals(verification.getEvidenceId())
                || !asset.getSha256().equals(verification.getExpectedSha256())
                || !asset.getSha256().equals(verification.getVerifiedSha256())
                || verification.getVerifiedByteSize() == null
                || asset.getByteSize() != verification.getVerifiedByteSize()
                || !bound.snapshot().getManifestSha256().equals(verification.getManifestSha256())
                || preservation.getPreservationStatus() != EvidencePreservationRecord.Status.IMMUTABLE_VERIFIED
                || !asset.getEvidenceId().equals(preservation.getEvidenceId())
                || !verification.getId().equals(preservation.getEvidenceVerificationRecordId())
                || !asset.getSha256().equals(preservation.getObjectSha256())
                || asset.getByteSize() != preservation.getObjectSize()
                || !CaseSnapshotManifest.sha256(preservationKey(bound, asset))
                        .equals(preservation.getObjectKeySha256())) throw stale();

        EvidenceObjectStorage.ByteVerification original;
        try {
            original = evidenceStorage.verifyBytes(asset.getObjectKey(), asset.getObjectVersion(), maximumBytes);
        } catch (S3EvidenceObjectStorage.ObjectMarkerChangedException
                 | S3EvidenceObjectStorage.ObjectSizeExceededException changed) {
            throw stale();
        } catch (S3Exception unavailable) {
            if (unavailable.statusCode() == 404 || unavailable.statusCode() == 412) throw stale();
            throw unavailable();
        } catch (RuntimeException unavailable) {
            throw unavailable();
        }
        if (original == null || original.byteSize() != asset.getByteSize()
                || !Objects.equals(original.mediaType(), asset.getMediaType())
                || !Objects.equals(original.sha256(), asset.getSha256())
                || !Objects.equals(original.marker(), asset.getObjectVersion())) throw stale();

        EvidencePreservationStorage.LockedObject locked;
        try {
            locked = preservationStorage.verifyLocked(preservationKey(bound, asset), preservation.getActualVersionId(),
                    preservation.getObjectSha256(), preservation.getObjectSize(), maximumBytes);
        } catch (EvidencePreservationStorage.ObjectMismatchException changed) {
            throw stale();
        } catch (EvidencePreservationStorage.ObjectLockUnsupportedException
                 | EvidencePreservationStorage.ObjectLockNotVerifiedException invalid) {
            throw blocked();
        } catch (EvidencePreservationStorage.StorageUnavailableException unavailable) {
            throw unavailable();
        } catch (RuntimeException unavailable) {
            throw unavailable();
        }
        if (locked == null || !Objects.equals(locked.versionId(), preservation.getActualVersionId())
                || !Objects.equals(locked.sha256(), preservation.getObjectSha256())
                || locked.byteSize() != preservation.getObjectSize()
                || locked.lockMode() != preservation.getObjectLockMode()
                || locked.retainUntil() == null || !locked.retainUntil().isAfter(now)
                || locked.retainUntil().isBefore(preservation.getRetainUntil())
                || !Objects.equals(preservationStorage.profile(), preservation.getStorageProfile())) throw blocked();
    }

    private static String preservationKey(OfficialPdfEligibilityValidator.Bound bound, EvidenceAsset asset) {
        return "preserved-evidence/" + bound.target().getTenantId() + "/" + bound.target().getEventId() + "/"
                + bound.target().getId() + "/" + asset.getEvidenceId();
    }

    private static CaseApiException stale() {
        return new CaseApiException(HttpStatus.CONFLICT, "APPROVAL_BASIS_STALE");
    }

    private static CaseApiException blocked() {
        return new CaseApiException(HttpStatus.UNPROCESSABLE_ENTITY, "APPROVAL_BASIS_BLOCKED");
    }

    private static CaseApiException unavailable() {
        return new CaseApiException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE");
    }
}
