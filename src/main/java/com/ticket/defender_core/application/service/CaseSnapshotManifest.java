package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.CaseDto;
import com.ticket.defender_core.adapter.out.persistence.EvidenceAssetRepository;
import com.ticket.defender_core.adapter.out.persistence.FanReportRepository;
import com.ticket.defender_core.adapter.out.persistence.MacroAuditEvidenceReadRepository;
import com.ticket.defender_core.domain.Case;
import com.ticket.defender_core.domain.CaseSourceReference;
import com.ticket.defender_core.domain.EvidenceAsset;
import com.ticket.defender_core.domain.EvidenceSnapshot;
import com.ticket.defender_core.domain.FanReport;
import com.ticket.defender_core.domain.MacroAuditEvidence;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class CaseSnapshotManifest {
    private final CaseSourceVerifier verifier;
    private final FanReportRepository reports;
    private final EvidenceAssetRepository assets;
    private final MacroAuditEvidenceReadRepository clusters;
    private final ObjectMapper json;

    public Captured capture(Case target, List<CaseSourceReference> references, LocalDateTime now) {
        return capture(target, references, now, null);
    }

    public void recheckDatabase(Case target, List<CaseSourceReference> references,
            Captured prepared, LocalDateTime now) {
        Captured current = captureForRecheck(target, references, now, prepared.assets());
        if (!prepared.sha256().equals(current.sha256())
                || !prepared.assets().equals(current.assets())) throw changed();
    }

    public void recheckStoredDatabase(Case target, List<CaseSourceReference> references,
            EvidenceSnapshot snapshot, LocalDateTime now) {
        Map<UUID, AssetState> states = new HashMap<>();
        for (CaseSourceReference reference : references) {
            if (reference.getSourceType() != CaseSourceReference.Type.EVIDENCE_ASSET) continue;
            EvidenceAsset asset = assets.findById(reference.getEvidenceId()).orElseThrow(CaseSnapshotManifest::changed);
            if (!Objects.equals(reference.getFanReportId(), asset.getFanReportId())
                    || !Objects.equals(reference.getObjectMarker(), asset.getObjectVersion())
                    || !Objects.equals(asset.getObjectKey(),
                    "fan-reports/" + asset.getFanReportId() + "/" + asset.getEvidenceId())) throw changed();
            states.put(asset.getEvidenceId(),
                    new AssetState(asset.getObjectKey(), asset.getObjectVersion(), reference.getObjectMarker()));
        }
        Captured current = captureForRecheck(target, references, now, states);
        if (snapshot.getManifestSchemaVersion() != current.schemaVersion()
                || !snapshot.getManifestSha256().equals(current.sha256())) throw changed();
    }

    private Captured captureForRecheck(Case target, List<CaseSourceReference> references,
            LocalDateTime now, Map<UUID, AssetState> checkedAssets) {
        try {
            return capture(target, references, now, checkedAssets);
        } catch (CaseApiException unavailable) {
            boolean candidateSource = references.stream().anyMatch(reference -> reference.getSourceType()
                    == CaseSourceReference.Type.LISTING_CANDIDATE_MATCH);
            if (candidateSource && ("SOURCE_NOT_AVAILABLE".equals(unavailable.code())
                    || "RESOURCE_NOT_FOUND".equals(unavailable.code()))) throw changed();
            throw unavailable;
        }
    }

    private Captured capture(Case target, List<CaseSourceReference> references, LocalDateTime now,
            Map<UUID, AssetState> checkedAssets) {
        if (references.isEmpty() || references.size() > 30 || references.stream().anyMatch(ref ->
                !target.getId().equals(ref.getCaseId()) || !target.getTenantId().equals(ref.getTenantId())
                        || !target.getEventId().equals(ref.getEventId()))) throw changed();
        List<CaseSourceReference> ordered = references.stream()
                .sorted(Comparator.comparing(CaseSourceReference::getSourceKeyDigest)).toList();
        CaseDto.Create sourceInput = new CaseDto.Create(target.getEventId(), target.getType(),
                ordered.stream().filter(ref -> ref.getSourceType() == CaseSourceReference.Type.STORED_CLUSTER_EVIDENCE)
                        .map(ref -> new CaseDto.GraphCluster(ref.getAnalysisRequestId(), ref.getClusterId())).toList(),
                ordered.stream().filter(ref -> ref.getSourceType() == CaseSourceReference.Type.FAN_REPORT)
                        .map(CaseSourceReference::getFanReportId).toList(),
                ordered.stream().filter(ref -> ref.getSourceType() == CaseSourceReference.Type.EVIDENCE_ASSET)
                        .map(CaseSourceReference::getEvidenceId).toList(),
                ordered.stream().filter(ref -> ref.getSourceType() == CaseSourceReference.Type.LISTING_CANDIDATE_MATCH)
                        .map(CaseSourceReference::getListingCandidateMatchRunId).toList(),
                "Snapshot capture", null, null);
        Map<UUID, String> markers = new HashMap<>();
        if (checkedAssets != null) checkedAssets.forEach((id, asset) -> markers.put(id, asset.checkedMarker()));
        List<CaseSourceReference> current = checkedAssets == null
                ? verifier.verify(target, sourceInput, now)
                : verifier.verifyDatabase(target, sourceInput, now, markers);
        Map<String, CaseSourceReference> byDigest = new java.util.HashMap<>();
        current.forEach(ref -> byDigest.put(ref.getSourceKeyDigest(), ref));
        if (byDigest.size() != ordered.size()) throw changed();

        List<Map<String, Object>> items = new ArrayList<>();
        Map<UUID, AssetState> assetStates = new HashMap<>();
        boolean hasFile = false;
        for (CaseSourceReference prior : ordered) {
            CaseSourceReference fresh = byDigest.get(prior.getSourceKeyDigest());
            if (fresh == null || fresh.getSourceType() != prior.getSourceType()) throw changed();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sourceReferenceId", prior.getId().toString());
            item.put("sourceType", prior.getSourceType().name());
            item.put("sourceKeySha256", prior.getSourceKeyDigest());
            switch (prior.getSourceType()) {
                case STORED_CLUSTER_EVIDENCE -> {
                    if (!Objects.equals(prior.getAnchorAuditId(), fresh.getAnchorAuditId())
                            || !Objects.equals(prior.getStoredMemberCount(), fresh.getStoredMemberCount())
                            || !Objects.equals(prior.getAnalysisAlgorithm(), fresh.getAnalysisAlgorithm())
                            || !Objects.equals(prior.getAnalysisAlgorithmVersion(), fresh.getAnalysisAlgorithmVersion())
                            || !Objects.equals(prior.getAnalysisCompletedAt(), fresh.getAnalysisCompletedAt())
                            || !Objects.equals(prior.getRiskScore(), fresh.getRiskScore())) throw changed();
                    List<MacroAuditEvidence> members = clusters.findMembers(target.getTenantId(), target.getEventId(),
                            prior.getAnalysisRequestId(), prior.getClusterId());
                    if (members.size() != prior.getStoredMemberCount()) throw changed();
                    item.put("analysisRequestId", prior.getAnalysisRequestId());
                    item.put("clusterId", prior.getClusterId());
                    item.put("anchorAuditId", prior.getAnchorAuditId());
                    item.put("algorithm", prior.getAnalysisAlgorithm());
                    item.put("algorithmVersion", prior.getAnalysisAlgorithmVersion());
                    item.put("analysisCompletedAt", utc(prior.getAnalysisCompletedAt()));
                    item.put("riskScore", prior.getRiskScore());
                    item.put("storedMemberCount", prior.getStoredMemberCount());
                    item.put("storedMembersSha256", sha256(json.writeValueAsString(
                            members.stream().map(member -> {
                                Map<String, Object> fingerprint = new LinkedHashMap<>();
                                fingerprint.put("auditId", member.getAuditId());
                                fingerprint.put("evidenceKey", member.getEvidenceKey());
                                fingerprint.put("riskScore", member.getRiskScore());
                                return fingerprint;
                            }).toList())));
                }
                case FAN_REPORT -> {
                    FanReport report = reports.findById(prior.getFanReportId()).orElseThrow(CaseSnapshotManifest::changed);
                    if (!scope(target, report) || report.getIntakeStatus() != FanReport.IntakeStatus.RECEIVED
                            || !report.getRetentionUntil().isAfter(now)) throw changed();
                    item.put("fanReportId", report.getFanReportId().toString());
                    item.put("intakeStatus", report.getIntakeStatus().name());
                    item.put("receivedAt", utc(report.getReceivedAt()));
                    item.put("retentionUntil", utc(report.getRetentionUntil()));
                }
                case EVIDENCE_ASSET -> {
                    EvidenceAsset asset = assets.findById(prior.getEvidenceId()).orElseThrow(CaseSnapshotManifest::changed);
                    if (!Objects.equals(prior.getFanReportId(), asset.getFanReportId())
                            || asset.getStorageStatus() != EvidenceAsset.StorageStatus.STORED
                            || !asset.getRetentionUntil().isAfter(now)
                            || !Objects.equals(prior.getObjectMarker(), fresh.getObjectMarker())) throw changed();
                    assetStates.put(asset.getEvidenceId(),
                            new AssetState(asset.getObjectKey(), asset.getObjectVersion(), fresh.getObjectMarker()));
                    hasFile = true;
                    item.put("evidenceId", asset.getEvidenceId().toString());
                    item.put("fanReportId", asset.getFanReportId().toString());
                    item.put("storageStatus", asset.getStorageStatus().name());
                    item.put("byteSize", asset.getByteSize());
                    item.put("mediaType", asset.getMediaType());
                    item.put("originalSha256", asset.getSha256());
                    item.put("opaqueObjectMarker", fresh.getObjectMarker());
                    item.put("retentionUntil", utc(asset.getRetentionUntil()));
                }
                case LISTING_CANDIDATE_MATCH -> {
                    if (!candidateSame(prior, fresh)) throw changed();
                    item.put("candidateRunId", prior.getListingCandidateMatchRunId().toString());
                    item.put("analysisId", prior.getCandidateAnalysisId().toString());
                    item.put("analysisRowVersion", prior.getCandidateAnalysisRowVersion());
                    item.put("analysisResponseSha256", prior.getCandidateAnalysisResponseSha256());
                    item.put("evidenceSha256", prior.getCandidateEvidenceSha256());
                    item.put("performanceLinkId", prior.getCandidatePerformanceLinkId().toString());
                    item.put("performanceLinkRevision", prior.getCandidatePerformanceLinkRevision());
                    item.put("verifiedPerformanceId", prior.getCandidateVerifiedPerformanceId().toString());
                    item.put("verifiedPerformanceRevision", prior.getCandidateVerifiedPerformanceRevision());
                    item.put("reservationSourceId", prior.getCandidateReservationSourceId().toString());
                    item.put("reservationSourceRevision", prior.getCandidateReservationSourceRevision());
                    item.put("reservationBatchId", prior.getCandidateReservationBatchId().toString());
                    item.put("reservationSourceVersion", prior.getCandidateReservationSourceVersion());
                    item.put("status", prior.getCandidateStatus());
                    item.put("reason", prior.getCandidateReason());
                    item.put("candidateCount", prior.getCandidateCount());
                    item.put("candidateId", prior.getCandidateId() == null ? null : prior.getCandidateId().toString());
                }
            }
            items.add(item);
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        int schemaVersion = ordered.stream().anyMatch(ref -> ref.getSourceType()
                == CaseSourceReference.Type.LISTING_CANDIDATE_MATCH) ? 2 : 1;
        manifest.put("manifestSchemaVersion", schemaVersion);
        manifest.put("tenantId", target.getTenantId().toString());
        manifest.put("eventId", target.getEventId().toString());
        manifest.put("caseId", target.getId().toString());
        manifest.put("evidenceRevision", target.getEvidenceRevision());
        manifest.put("sources", items);
        String serialized = json.writeValueAsString(manifest);
        if (serialized.getBytes(StandardCharsets.UTF_8).length > 60000) throw changed();
        return new Captured(schemaVersion, serialized, sha256(serialized), hasFile ? "METADATA_ONLY" : "NO_FILE",
                Map.copyOf(assetStates));
    }

    private static boolean scope(Case target, FanReport report) {
        return target.getTenantId().equals(report.getTenantId())
                && target.getEventId().equals(report.getEventId());
    }

    private static String utc(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC).toString();
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static CaseApiException changed() {
        return new CaseApiException(HttpStatus.CONFLICT, "SOURCE_CHANGED");
    }

    private static boolean candidateSame(CaseSourceReference a, CaseSourceReference b) {
        return Objects.equals(a.getListingCandidateMatchRunId(), b.getListingCandidateMatchRunId())
                && Objects.equals(a.getCandidateAnalysisId(), b.getCandidateAnalysisId())
                && Objects.equals(a.getCandidateAnalysisRowVersion(), b.getCandidateAnalysisRowVersion())
                && Objects.equals(a.getCandidateAnalysisResponseSha256(), b.getCandidateAnalysisResponseSha256())
                && Objects.equals(a.getCandidateEvidenceSha256(), b.getCandidateEvidenceSha256())
                && Objects.equals(a.getCandidatePerformanceLinkId(), b.getCandidatePerformanceLinkId())
                && Objects.equals(a.getCandidatePerformanceLinkRevision(), b.getCandidatePerformanceLinkRevision())
                && Objects.equals(a.getCandidateVerifiedPerformanceId(), b.getCandidateVerifiedPerformanceId())
                && Objects.equals(a.getCandidateVerifiedPerformanceRevision(), b.getCandidateVerifiedPerformanceRevision())
                && Objects.equals(a.getCandidateReservationSourceId(), b.getCandidateReservationSourceId())
                && Objects.equals(a.getCandidateReservationSourceRevision(), b.getCandidateReservationSourceRevision())
                && Objects.equals(a.getCandidateReservationBatchId(), b.getCandidateReservationBatchId())
                && Objects.equals(a.getCandidateReservationSourceVersion(), b.getCandidateReservationSourceVersion())
                && Objects.equals(a.getCandidateStatus(), b.getCandidateStatus())
                && Objects.equals(a.getCandidateReason(), b.getCandidateReason())
                && Objects.equals(a.getCandidateCount(), b.getCandidateCount())
                && Objects.equals(a.getCandidateId(), b.getCandidateId());
    }

    public record Captured(int schemaVersion, String json, String sha256, String sourceIntegrity,
                           Map<UUID, AssetState> assets) {
        public Captured(String json, String sha256, String sourceIntegrity, Map<UUID, AssetState> assets) {
            this(1, json, sha256, sourceIntegrity, assets);
        }
    }

    public record AssetState(String objectKey, String objectVersion, String checkedMarker) { }
}
