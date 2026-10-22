package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.CaseDto;
import com.ticket.defender_core.adapter.out.persistence.EvidenceAssetRepository;
import com.ticket.defender_core.adapter.out.persistence.FanReportRepository;
import com.ticket.defender_core.adapter.out.persistence.MacroAuditEvidenceReadRepository;
import com.ticket.defender_core.domain.Case;
import com.ticket.defender_core.domain.CaseSourceReference;
import com.ticket.defender_core.domain.EvidenceAsset;
import com.ticket.defender_core.domain.FanReport;
import com.ticket.defender_core.domain.MacroAuditEvidence;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CaseSourceVerifier {
    private final MacroAuditEvidenceReadRepository clusters;
    private final FanReportRepository reports;
    private final EvidenceAssetRepository assets;
    private final EvidenceObjectStorage storage;

    public List<CaseSourceReference> verify(Case target, CaseDto.Create input, LocalDateTime now) {
        List<CaseDto.GraphCluster> graphInputs = input.graphClusters() == null ? List.of() : input.graphClusters();
        List<UUID> reportIds = input.fanReportIds() == null ? List.of() : input.fanReportIds();
        List<UUID> assetIds = input.evidenceIds() == null ? List.of() : input.evidenceIds();
        if (graphInputs.size() + reportIds.size() + assetIds.size() > 30
                || graphInputs.stream().anyMatch(Objects::isNull)
                || reportIds.stream().anyMatch(Objects::isNull)
                || assetIds.stream().anyMatch(Objects::isNull)) throw invalid();
        if (switch (target.getType()) {
            case GRAPH_CLUSTER -> graphInputs.isEmpty() || !reportIds.isEmpty() || !assetIds.isEmpty();
            case FAN_REPORT -> !graphInputs.isEmpty() || reportIds.isEmpty();
            case COMBINED -> graphInputs.isEmpty() || reportIds.isEmpty();
        }) throw invalid();
        if (!assetIds.isEmpty() && reportIds.isEmpty()) throw invalid();

        List<CaseSourceReference> result = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        Set<UUID> verifiedReports = new HashSet<>();
        for (CaseDto.GraphCluster source : graphInputs) {
            if (source.analysisRequestId() == null || source.clusterId() == null
                    || source.clusterId().isBlank() || source.clusterId().length() > 191) throw invalid();
            try {
                if (!UUID.fromString(source.analysisRequestId()).toString().equals(source.analysisRequestId()))
                    throw invalid();
            } catch (IllegalArgumentException invalidId) {
                throw invalid();
            }
            List<MacroAuditEvidence> members = clusters.findMembers(target.getTenantId(), target.getEventId(),
                    source.analysisRequestId(), source.clusterId());
            if (members.isEmpty()) throw notFound();
            MacroAuditEvidence first = members.getFirst();
            if (first.getAuditId() == null || first.getAnalysisCompletedAt() == null
                    || first.getAnalysisAlgorithm() == null || first.getAnalysisAlgorithmVersion() == null
                    || first.getRiskScore() == null || members.stream().anyMatch(row ->
                    !Objects.equals(first.getAnalysisAlgorithm(), row.getAnalysisAlgorithm())
                    || !Objects.equals(first.getAnalysisAlgorithmVersion(), row.getAnalysisAlgorithmVersion())
                    || !Objects.equals(first.getAnalysisCompletedAt(), row.getAnalysisCompletedAt())
                    || !Objects.equals(first.getRiskScore(), row.getRiskScore()))) throw invalid();
            String digest = digest(target, "GRAPH", source.analysisRequestId(), source.clusterId());
            unique(keys, digest);
            result.add(CaseSourceReference.cluster(target, digest, first, members.size(), now));
        }
        for (UUID reportId : reportIds) {
            FanReport report = verifiedReport(target, reportId, now);
            verifiedReports.add(report.getFanReportId());
            String digest = digest(target, "REPORT", reportId.toString());
            unique(keys, digest);
            result.add(CaseSourceReference.report(target, digest, reportId, now));
        }
        for (UUID evidenceId : assetIds) {
            EvidenceAsset asset = assets.findById(evidenceId).orElseThrow(CaseSourceVerifier::notFound);
            if (!verifiedReports.contains(asset.getFanReportId())) throw notFound();
            if (asset.getStorageStatus() != EvidenceAsset.StorageStatus.STORED
                    || !asset.getRetentionUntil().isAfter(now))
                throw new CaseApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SOURCE_NOT_AVAILABLE");
            EvidenceObjectStorage.ObjectMetadata metadata;
            try {
                metadata = storage.head(asset.getObjectKey());
            } catch (S3Exception unavailable) {
                if (unavailable.statusCode() == 404) {
                    // A 404 can also mean that the bucket itself is gone. Confirm its
                    // availability before classifying this as an absent object.
                    try {
                        storage.checkReady();
                    } catch (RuntimeException bucketUnavailable) {
                        throw new CaseApiException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE");
                    }
                    throw new CaseApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SOURCE_OBJECT_MISSING");
                }
                throw new CaseApiException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE");
            } catch (RuntimeException unavailable) {
                throw new CaseApiException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE");
            }
            if (metadata == null)
                throw new CaseApiException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE");
            if (metadata.byteSize() != asset.getByteSize()
                    || !Objects.equals(metadata.mediaType(), asset.getMediaType())
                    || (asset.getObjectVersion() != null
                    && !Objects.equals(asset.getObjectVersion(), metadata.opaqueMarker())))
                throw new CaseApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SOURCE_OBJECT_MISMATCH");
            String digest = digest(target, "ASSET", evidenceId.toString());
            unique(keys, digest);
            result.add(CaseSourceReference.asset(target, digest, asset.getFanReportId(),
                    evidenceId, metadata.opaqueMarker(), now));
        }
        return result;
    }

    private FanReport verifiedReport(Case target, UUID id, LocalDateTime now) {
        FanReport report = reports.findById(id).orElseThrow(CaseSourceVerifier::notFound);
        if (!target.getTenantId().equals(report.getTenantId())
                || !target.getEventId().equals(report.getEventId())) throw notFound();
        if (report.getIntakeStatus() != FanReport.IntakeStatus.RECEIVED
                || !report.getRetentionUntil().isAfter(now))
            throw new CaseApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SOURCE_NOT_AVAILABLE");
        return report;
    }

    private static String digest(Case target, String type, String... parts) {
        StringBuilder canonical = new StringBuilder(type).append('|').append(target.getTenantId())
                .append('|').append(target.getEventId());
        for (String part : parts) canonical.append('|').append(part.length()).append(':').append(part);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void unique(Set<String> keys, String key) {
        if (!keys.add(key)) throw invalid();
    }

    private static CaseApiException invalid() {
        return new CaseApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED");
    }

    private static CaseApiException notFound() {
        return new CaseApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }
}
