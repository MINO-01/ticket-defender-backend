package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.EvidenceIntegrityDto;
import com.ticket.defender_core.adapter.out.persistence.CaseIdempotencyRepository;
import com.ticket.defender_core.adapter.out.persistence.CaseRepository;
import com.ticket.defender_core.adapter.out.persistence.CaseSourceReferenceRepository;
import com.ticket.defender_core.adapter.out.persistence.EvidenceAssetRepository;
import com.ticket.defender_core.adapter.out.persistence.EvidenceSnapshotRepository;
import com.ticket.defender_core.adapter.out.persistence.EvidenceVerificationRecordRepository;
import com.ticket.defender_core.adapter.out.persistence.FanReportRepository;
import com.ticket.defender_core.adapter.out.persistence.ReportVersionRepository;
import com.ticket.defender_core.adapter.out.storage.S3EvidenceObjectStorage;
import com.ticket.defender_core.domain.Case;
import com.ticket.defender_core.domain.CaseIdempotencyRecord;
import com.ticket.defender_core.domain.CaseSourceReference;
import com.ticket.defender_core.domain.EvidenceAsset;
import com.ticket.defender_core.domain.EvidenceSnapshot;
import com.ticket.defender_core.domain.EvidenceVerificationRecord;
import com.ticket.defender_core.domain.FanReport;
import com.ticket.defender_core.domain.ReportVersion;
import com.ticket.defender_core.security.AdminAccessService;
import com.ticket.defender_core.security.AdminResourceScope;
import com.ticket.defender_core.security.AdminRole;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.model.S3Exception;
import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class EvidenceIntegrityService {
    private static final Pattern KEY = Pattern.compile(
            "(?i)[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    private static final Set<AdminRole> REVIEWER = Set.of(AdminRole.REVIEWER);
    private static final long UPLOAD_LIMIT = 20L * 1024 * 1024;

    private final CaseService caseService;
    private final AdminAccessService access;
    private final CaseMembershipAccess memberships;
    private final CaseRepository cases;
    private final EvidenceIntegrityContext context;
    private final EvidenceIntegrityWriteService writer;
    private final EvidenceObjectStorage storage;
    private final EvidenceVerificationRecordRepository records;
    private final EvidenceSnapshotRepository snapshots;
    private final ReportVersionRepository drafts;
    private final CaseSourceReferenceRepository sources;
    private final EvidenceAssetRepository assets;
    private final FanReportRepository reports;
    private final CaseSnapshotManifest manifests;
    private final CaseIdempotencyRepository idempotency;
    private final ObjectMapper json;

    @Value("${ticket-defender.evidence-integrity.max-bytes:20971520}")
    private long maxBytes;

    public EvidenceIntegrityDto.Verification verify(UUID caseId, EvidenceIntegrityDto.VerifyInput input, String key) {
        if (input == null || input.expectedCaseVersion() == null || input.evidenceSnapshotId() == null
                || input.sourceReferenceId() == null || input.evidenceId() == null)
            throw invalid();
        CasePreparationWriteService.validate(input.reason(), 3, 1800);
        UUID actor = reviewer(caseId);
        Prepared prepared = prepare(key, input);
        EvidenceVerificationRecord prior = replay(caseId, actor, prepared);
        if (prior != null) return result(prior);
        EvidenceIntegrityContext.Checked checked = context.load(caseId, input.evidenceSnapshotId(),
                input.sourceReferenceId(), input.evidenceId(), input.expectedCaseVersion(), actor);
        Outcome outcome = read(checked);
        try {
            EvidenceVerificationRecord stored = writer.record(caseId, input, checked, outcome,
                    prepared.keyDigest(), prepared.requestDigest());
            return result(stored);
        } catch (CaseApiException collision) {
            EvidenceVerificationRecord committed = replay(caseId, reviewer(caseId), prepared);
            if (committed != null) return result(committed);
            throw collision;
        } catch (DataIntegrityViolationException collision) {
            EvidenceVerificationRecord committed = replay(caseId, reviewer(caseId), prepared);
            if (committed != null) return result(committed);
            throw collision;
        } catch (OptimisticLockingFailureException collision) {
            EvidenceVerificationRecord committed = replay(caseId, reviewer(caseId), prepared);
            if (committed != null) return result(committed);
            throw new CaseApiException(HttpStatus.CONFLICT, "CASE_VERSION_CONFLICT");
        }
    }

    @Transactional(readOnly = true)
    public EvidenceIntegrityDto.VerificationPage records(UUID caseId, int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE)
            throw new CaseApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        caseService.detail(caseId);
        var found = records.findByCaseId(caseId, PageRequest.of(page, size,
                Sort.by("verifiedAt").descending().and(Sort.by("id").descending())));
        return new EvidenceIntegrityDto.VerificationPage(found.stream()
                .map(EvidenceIntegrityDto.Verification::from).toList(), page, size, found.hasNext());
    }

    public EvidenceIntegrityDto.Evaluation evaluate(UUID caseId, UUID reportVersionId) {
        if (reportVersionId == null) throw invalid();
        caseService.detail(caseId);
        Case target = cases.findById(caseId).orElseThrow(EvidenceIntegrityService::notFound);
        ReportVersion draft = drafts.findById(reportVersionId).orElseThrow(EvidenceIntegrityService::notFound);
        if (!caseId.equals(draft.getCaseId()) || !target.getTenantId().equals(draft.getTenantId())
                || !target.getEventId().equals(draft.getEventId())) throw notFound();
        EvidenceSnapshot snapshot = snapshots.findById(draft.getEvidenceSnapshotId())
                .orElseThrow(EvidenceIntegrityService::notFound);
        if (!caseId.equals(snapshot.getCaseId()) || !target.getTenantId().equals(snapshot.getTenantId())
                || !target.getEventId().equals(snapshot.getEventId())) throw notFound();
        Set<String> reasons = new LinkedHashSet<>();
        reasons.add("POLICY_UNAPPROVED");
        if (target.getStatus() != Case.Status.IN_REVIEW) reasons.add("CASE_STATE_NOT_ELIGIBLE");
        if (target.getEvidenceRevision() != snapshot.getEvidenceRevision()
                || draft.getEvidenceRevision() != snapshot.getEvidenceRevision())
            reasons.add("EVIDENCE_REVISION_CHANGED");
        if (!CaseSnapshotManifest.sha256(snapshot.getManifestJson()).equals(snapshot.getManifestSha256()))
            reasons.add("SNAPSHOT_DIGEST_MISMATCH");
        if (!CaseSnapshotManifest.sha256(draft.getContentJson()).equals(draft.getContentSha256())
                || !contentBinds(draft, snapshot)) reasons.add("REPORT_DIGEST_MISMATCH");
        if (!"DRAFT".equals(draft.getStatus()) || !"draft-input-v1".equals(draft.getTemplateVersion()))
            reasons.add("TEMPLATE_NOT_RETAINED");

        List<CaseSourceReference> references = sources.findByCaseIdOrderByLinkedAtAscIdAsc(caseId);
        boolean hasFile = false;
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        for (CaseSourceReference ref : references) {
            if (ref.getSourceType() == CaseSourceReference.Type.FAN_REPORT) {
                FanReport report = reports.findById(ref.getFanReportId()).orElse(null);
                if (report == null || !target.getTenantId().equals(report.getTenantId())
                        || !target.getEventId().equals(report.getEventId())
                        || report.getIntakeStatus() != FanReport.IntakeStatus.RECEIVED)
                    reasons.add("SOURCE_CHANGED");
                else if (!report.getRetentionUntil().isAfter(now)) reasons.add("RETENTION_EXPIRED");
            }
            if (ref.getSourceType() != CaseSourceReference.Type.EVIDENCE_ASSET) continue;
            hasFile = true;
            EvidenceAsset asset = assets.findById(ref.getEvidenceId()).orElse(null);
            if (asset == null || !ref.getFanReportId().equals(asset.getFanReportId())) {
                reasons.add("SOURCE_CHANGED");
                continue;
            }
            if (asset.getStorageStatus() != EvidenceAsset.StorageStatus.STORED)
                reasons.add("SOURCE_CHANGED");
            if (!asset.getRetentionUntil().isAfter(now)) reasons.add("RETENTION_EXPIRED");
            EvidenceVerificationRecord latest = records
                    .findTopByEvidenceSnapshotIdAndSourceReferenceIdOrderByVerifiedAtDescIdDesc(
                            snapshot.getId(), ref.getId()).orElse(null);
            if (latest == null) {
                reasons.add("BYTE_CHECK_REQUIRED");
            } else if (latest.getVerificationStatus() != EvidenceVerificationRecord.Status.MATCH) {
                reasons.add("BYTE_CHECK_" + latest.getVerificationStatus().name());
            } else if (!snapshot.getManifestSha256().equals(latest.getManifestSha256())
                    || !asset.getSha256().equals(latest.getVerifiedSha256())
                    || !asset.getSha256().equals(latest.getExpectedSha256())
                    || latest.getVerifiedByteSize() == null
                    || asset.getByteSize() != latest.getVerifiedByteSize()
                    || !CaseSnapshotManifest.sha256(asset.getObjectKey()).equals(latest.getObjectKeySha256())) {
                reasons.add("VERIFICATION_STALE");
            } else {
                reasons.add("ORIGINAL_NOT_IMMUTABLE");
            }
        }
        try {
            var current = manifests.capture(target, references, now);
            if (!snapshot.getManifestSha256().equals(current.sha256())) reasons.add("SOURCE_CHANGED");
        } catch (CaseApiException sourceError) {
            reasons.add(sourceError.code());
        }
        String technical;
        if (reasons.contains("DEPENDENCY_UNAVAILABLE")) technical = "UNAVAILABLE";
        else if (reasons.stream().anyMatch(reason -> !reason.equals("POLICY_UNAPPROVED")
                && !reason.equals("ORIGINAL_NOT_IMMUTABLE"))) technical = "BLOCKED";
        else technical = hasFile ? "VERIFIED_AT_TIME" : "METADATA_VALID";
        return new EvidenceIntegrityDto.Evaluation(caseId, snapshot.getId(), draft.getId(),
                technical, "BLOCKED", List.copyOf(reasons));
    }

    @SuppressWarnings("unchecked")
    private boolean contentBinds(ReportVersion draft, EvidenceSnapshot snapshot) {
        try {
            Map<String, Object> content = json.readValue(draft.getContentJson(), Map.class);
            return Objects.equals(content.get("evidenceSnapshotId"), snapshot.getId().toString())
                    && Objects.equals(content.get("manifestSha256"), snapshot.getManifestSha256())
                    && Objects.equals(content.get("templateVersion"), draft.getTemplateVersion());
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    private Outcome read(EvidenceIntegrityContext.Checked checked) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (checked.expired(now)) return new Outcome(EvidenceVerificationRecord.Status.RETENTION_EXPIRED,
                null, null, null, EvidenceVerificationRecord.MarkerKind.NONE);
        if (maxBytes < 1 || maxBytes > UPLOAD_LIMIT)
            throw new IllegalStateException("Invalid evidence byte verification limit");
        try {
            EvidenceObjectStorage.ByteVerification bytes = storage.verifyBytes(
                    checked.asset().getObjectKey(), checked.asset().getObjectVersion(), maxBytes);
            EvidenceVerificationRecord.Status status;
            if (bytes.byteSize() != checked.asset().getByteSize())
                status = EvidenceVerificationRecord.Status.SIZE_MISMATCH;
            else if (!Objects.equals(bytes.mediaType(), checked.asset().getMediaType()))
                status = EvidenceVerificationRecord.Status.OBJECT_CHANGED;
            else if (!sameDigest(bytes.sha256(), checked.asset().getSha256()))
                status = EvidenceVerificationRecord.Status.HASH_MISMATCH;
            else status = EvidenceVerificationRecord.Status.MATCH;
            return new Outcome(status, bytes.sha256(), bytes.byteSize(), bytes.marker(),
                    bytes.markerKind() == EvidenceObjectStorage.MarkerKind.VERSION_ID
                            ? EvidenceVerificationRecord.MarkerKind.VERSION_ID
                            : EvidenceVerificationRecord.MarkerKind.ETAG);
        } catch (S3EvidenceObjectStorage.ObjectMarkerChangedException changed) {
            return new Outcome(EvidenceVerificationRecord.Status.OBJECT_CHANGED, null, null, null,
                    EvidenceVerificationRecord.MarkerKind.NONE);
        } catch (S3EvidenceObjectStorage.ObjectSizeExceededException exceeded) {
            return new Outcome(EvidenceVerificationRecord.Status.TOO_LARGE, null, null, null,
                    EvidenceVerificationRecord.MarkerKind.NONE);
        } catch (S3Exception error) {
            if (error.statusCode() == 404) {
                try {
                    storage.checkReady();
                    return new Outcome(EvidenceVerificationRecord.Status.OBJECT_MISSING, null, null, null,
                            EvidenceVerificationRecord.MarkerKind.NONE);
                } catch (RuntimeException unavailable) {
                    return unavailable();
                }
            }
            if (error.statusCode() == 412)
                return new Outcome(EvidenceVerificationRecord.Status.OBJECT_CHANGED, null, null, null,
                        EvidenceVerificationRecord.MarkerKind.NONE);
            return unavailable();
        } catch (RuntimeException failure) {
            return unavailable();
        }
    }

    private static Outcome unavailable() {
        return new Outcome(EvidenceVerificationRecord.Status.STORAGE_UNAVAILABLE, null, null, null,
                EvidenceVerificationRecord.MarkerKind.NONE);
    }

    private static boolean sameDigest(String actual, String expected) {
        try {
            return MessageDigest.isEqual(HexFormat.of().parseHex(actual), HexFormat.of().parseHex(expected));
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    private EvidenceIntegrityDto.Verification result(EvidenceVerificationRecord record) {
        if (record.getVerificationStatus() == EvidenceVerificationRecord.Status.STORAGE_UNAVAILABLE)
            throw new CaseApiException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE");
        if (record.getVerificationStatus() != EvidenceVerificationRecord.Status.MATCH)
            throw new CaseApiException(HttpStatus.UNPROCESSABLE_ENTITY, record.getVerificationStatus().name());
        return EvidenceIntegrityDto.Verification.from(record);
    }

    private UUID reviewer(UUID caseId) {
        Case target = cases.findById(caseId).orElseThrow(EvidenceIntegrityService::notFound);
        var actor = access.requireResourceAccess(
                new AdminResourceScope(target.getTenantId(), target.getEventId()), null, REVIEWER);
        return memberships.actor(actor).getId();
    }

    private Prepared prepare(String key, EvidenceIntegrityDto.VerifyInput input) {
        if (key == null || !KEY.matcher(key).matches())
            throw new CaseApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY");
        return new Prepared(CaseSnapshotManifest.sha256(key.toLowerCase()),
                CaseSnapshotManifest.sha256(json.writeValueAsString(input)));
    }

    private EvidenceVerificationRecord replay(UUID caseId, UUID actor, Prepared prepared) {
        Case target = cases.findById(caseId).orElseThrow(EvidenceIntegrityService::notFound);
        CaseIdempotencyRecord record = idempotency
                .findByTenantIdAndActorMembershipIdAndOperationAndTargetCaseIdAndKeyDigest(
                        target.getTenantId(), actor, "EVIDENCE_VERIFY", caseId, prepared.keyDigest())
                .orElse(null);
        if (record == null) return null;
        if (!record.getRequestDigest().equals(prepared.requestDigest()))
            throw new CaseApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED");
        UUID resultId = json.readValue(record.getResponseJson(), VerificationReference.class).recordId();
        EvidenceVerificationRecord result = records.findById(resultId).orElseThrow(EvidenceIntegrityService::notFound);
        if (!caseId.equals(result.getCaseId()) || !target.getTenantId().equals(result.getTenantId())
                || !target.getEventId().equals(result.getEventId())) throw notFound();
        return result;
    }

    private static CaseApiException invalid() {
        return new CaseApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED");
    }
    private static CaseApiException notFound() {
        return new CaseApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }

    public record Outcome(EvidenceVerificationRecord.Status status, String sha256, Long byteSize,
                          String marker, EvidenceVerificationRecord.MarkerKind markerKind) { }
    private record Prepared(String keyDigest, String requestDigest) { }
    private record VerificationReference(UUID recordId) { }
}
