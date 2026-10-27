package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.persistence.CaseRepository;
import com.ticket.defender_core.adapter.out.persistence.CaseSourceReferenceRepository;
import com.ticket.defender_core.adapter.out.persistence.EvidenceAssetRepository;
import com.ticket.defender_core.adapter.out.persistence.EvidenceSnapshotRepository;
import com.ticket.defender_core.adapter.out.persistence.FanReportRepository;
import com.ticket.defender_core.domain.Case;
import com.ticket.defender_core.domain.CaseSourceReference;
import com.ticket.defender_core.domain.EvidenceAsset;
import com.ticket.defender_core.domain.EvidenceSnapshot;
import com.ticket.defender_core.domain.FanReport;
import com.ticket.defender_core.security.AdminAccessService;
import com.ticket.defender_core.security.AdminResourceScope;
import com.ticket.defender_core.security.AdminRole;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class EvidenceIntegrityContext {
    private static final Set<AdminRole> REVIEWER = Set.of(AdminRole.REVIEWER);

    private final CaseRepository cases;
    private final EvidenceSnapshotRepository snapshots;
    private final CaseSourceReferenceRepository sources;
    private final FanReportRepository reports;
    private final EvidenceAssetRepository assets;
    private final AdminAccessService access;
    private final CaseMembershipAccess memberships;
    private final CaseSnapshotManifest manifests;

    public Checked load(UUID caseId, UUID snapshotId, UUID sourceId, UUID evidenceId,
            Long expectedVersion, UUID expectedActor) {
        Case target = cases.findById(caseId).orElseThrow(EvidenceIntegrityContext::notFound);
        var actor = access.requireResourceAccess(
                new AdminResourceScope(target.getTenantId(), target.getEventId()), null, REVIEWER);
        UUID memberId = memberships.actor(actor).getId();
        if (expectedActor != null && !expectedActor.equals(memberId)) throw notFound();
        if (expectedVersion == null) throw invalid();
        if (!expectedVersion.equals(target.getCaseVersion())) throw conflict("CASE_VERSION_CONFLICT");
        if (target.getStatus() != Case.Status.IN_REVIEW) throw conflict("CASE_STATE_CONFLICT");
        EvidenceSnapshot snapshot = snapshots.findById(snapshotId).orElseThrow(EvidenceIntegrityContext::notFound);
        if (!caseId.equals(snapshot.getCaseId()) || !target.getTenantId().equals(snapshot.getTenantId())
                || !target.getEventId().equals(snapshot.getEventId())) throw notFound();
        if (snapshot.getEvidenceRevision() != target.getEvidenceRevision())
            throw conflict("EVIDENCE_VERSION_MISMATCH");
        if (snapshot.getManifestSchemaVersion() != 1 || !CaseSnapshotManifest.sha256(
                snapshot.getManifestJson()).equals(snapshot.getManifestSha256())) throw conflict("SOURCE_CHANGED");
        CaseSourceReference source = sources.findById(sourceId).orElseThrow(EvidenceIntegrityContext::notFound);
        if (source.getSourceType() != CaseSourceReference.Type.EVIDENCE_ASSET
                || !caseId.equals(source.getCaseId()) || !target.getTenantId().equals(source.getTenantId())
                || !target.getEventId().equals(source.getEventId()) || !evidenceId.equals(source.getEvidenceId()))
            throw notFound();
        EvidenceAsset asset = assets.findById(evidenceId).orElseThrow(EvidenceIntegrityContext::notFound);
        FanReport report = reports.findById(asset.getFanReportId()).orElseThrow(EvidenceIntegrityContext::notFound);
        if (!source.getFanReportId().equals(asset.getFanReportId())
                || !target.getTenantId().equals(report.getTenantId())
                || !target.getEventId().equals(report.getEventId())) throw notFound();
        if (report.getIntakeStatus() != FanReport.IntakeStatus.RECEIVED
                || asset.getStorageStatus() != EvidenceAsset.StorageStatus.STORED)
            throw new CaseApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SOURCE_NOT_AVAILABLE");
        if (!asset.getObjectKey().equals("fan-reports/" + asset.getFanReportId() + "/" + asset.getEvidenceId()))
            throw conflict("SOURCE_CHANGED");
        if (!Objects.equals(source.getObjectMarker(), asset.getObjectVersion()))
            throw conflict("SOURCE_CHANGED");
        if (snapshot.getManifestSchemaVersion() != 1) throw conflict("SOURCE_CHANGED");
        manifests.recheckStoredDatabase(target, sources.findByCaseIdOrderByLinkedAtAscIdAsc(caseId),
                snapshot, snapshot.getCapturedAt());
        Fingerprint fingerprint = new Fingerprint(target.getCaseVersion(), target.getEvidenceRevision(),
                snapshot.getManifestSha256(), source.getSourceKeyDigest(), source.getObjectMarker(),
                report.getIntakeStatus(), report.getRetentionUntil(), asset.getStorageStatus(),
                asset.getObjectKey(), asset.getObjectVersion(), asset.getSha256(), asset.getByteSize(),
                asset.getMediaType(), asset.getRetentionUntil());
        return new Checked(target, snapshot, source, report, asset, memberId, fingerprint);
    }

    public record Checked(Case target, EvidenceSnapshot snapshot, CaseSourceReference source,
                          FanReport report, EvidenceAsset asset, UUID actorMembershipId,
                          Fingerprint fingerprint) {
        public boolean expired(LocalDateTime now) {
            return !report.getRetentionUntil().isAfter(now) || !asset.getRetentionUntil().isAfter(now);
        }
        public LocalDateTime retentionUntil() {
            return report.getRetentionUntil().isBefore(asset.getRetentionUntil())
                    ? report.getRetentionUntil() : asset.getRetentionUntil();
        }
    }

    public record Fingerprint(Long caseVersion, long evidenceRevision, String manifestSha256,
                              String sourceKeyDigest, String sourceMarker,
                              FanReport.IntakeStatus reportStatus, LocalDateTime reportRetention,
                              EvidenceAsset.StorageStatus assetStatus, String objectKey, String objectVersion,
                              String assetSha256, long byteSize, String mediaType,
                              LocalDateTime assetRetention) { }

    private static CaseApiException notFound() {
        return new CaseApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }
    private static CaseApiException invalid() {
        return new CaseApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED");
    }
    private static CaseApiException conflict(String code) {
        return new CaseApiException(HttpStatus.CONFLICT, code);
    }
}
