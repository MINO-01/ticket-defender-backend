package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.CasePreparationDto;
import com.ticket.defender_core.adapter.out.persistence.CaseActivityRepository;
import com.ticket.defender_core.adapter.out.persistence.CaseIdempotencyRepository;
import com.ticket.defender_core.adapter.out.persistence.CaseRepository;
import com.ticket.defender_core.adapter.out.persistence.CaseSourceReferenceRepository;
import com.ticket.defender_core.adapter.out.persistence.EvidenceSnapshotRepository;
import com.ticket.defender_core.adapter.out.persistence.ReportVersionRepository;
import com.ticket.defender_core.domain.Case;
import com.ticket.defender_core.domain.CaseActivity;
import com.ticket.defender_core.domain.CaseIdempotencyRecord;
import com.ticket.defender_core.domain.EvidenceSnapshot;
import com.ticket.defender_core.domain.ReportVersion;
import com.ticket.defender_core.security.AdminAccessService;
import com.ticket.defender_core.security.AdminResourceScope;
import com.ticket.defender_core.security.AdminRole;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CasePreparationWriteService {
    private static final Set<AdminRole> REVIEWER = Set.of(AdminRole.REVIEWER);
    private final AdminAccessService access;
    private final CaseMembershipAccess memberships;
    private final CaseRepository cases;
    private final CaseSourceReferenceRepository sources;
    private final CaseSnapshotManifest manifests;
    private final ReportTemplateRegistry templates;
    private final EvidenceSnapshotRepository snapshots;
    private final ReportVersionRepository drafts;
    private final CaseActivityRepository activities;
    private final CaseIdempotencyRepository idempotency;
    private final ObjectMapper json;

    @Transactional
    public CasePreparationDto.Snapshot capture(UUID caseId, CasePreparationDto.Capture input,
            UUID memberId, String keyDigest, String requestDigest, CaseSnapshotManifest.Captured manifest) {
        Case target = reviewerCase(caseId, memberId, input.expectedCaseVersion());
        if (target.getStatus() != Case.Status.IN_REVIEW) throw conflict("CASE_STATE_CONFLICT");
        validate(input.reason(), 3, 1800);
        if (snapshots.findByCaseIdAndEvidenceRevision(caseId, target.getEvidenceRevision()).isPresent())
            throw conflict("SNAPSHOT_ALREADY_EXISTS");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        manifests.recheckDatabase(target, sources.findByCaseIdOrderByLinkedAtAscIdAsc(caseId), manifest, now);
        long before = target.getCaseVersion();
        target.recordPreparation(now);
        cases.saveAndFlush(target);
        EvidenceSnapshot snapshot = snapshots.saveAndFlush(new EvidenceSnapshot(target, target.getCaseVersion(),
                manifest.sha256(), manifest.json(), manifest.sourceIntegrity(), memberId, now));
        activities.saveAndFlush(new CaseActivity(target, memberId, AdminRole.REVIEWER.name(),
                "EVIDENCE_SNAPSHOT_CAPTURED", "snapshot=" + snapshot.getId() + "; " + input.reason().trim(),
                target.getStatus(), target.getStatus(), before, target.getCaseVersion(), null, now));
        CasePreparationDto.Snapshot response = CasePreparationDto.Snapshot.from(snapshot);
        idempotency.saveAndFlush(new CaseIdempotencyRecord(target.getTenantId(), memberId, "SNAPSHOT_CREATE",
                caseId, keyDigest, requestDigest, caseId,
                json.writeValueAsString(Map.of("evidenceSnapshotId", snapshot.getId())), now));
        return response;
    }

    @Transactional
    public CasePreparationDto.Draft draft(UUID caseId, CasePreparationDto.DraftInput input,
            UUID memberId, String keyDigest, String requestDigest, CaseSnapshotManifest.Captured prepared) {
        Case target = reviewerCase(caseId, memberId, input.expectedCaseVersion());
        if (target.getStatus() != Case.Status.IN_REVIEW) throw conflict("CASE_STATE_CONFLICT");
        validateReportText(input.title(), 3, 160);
        validateReportText(input.assessment(), 10, 6000);
        validateReportText(input.limitations(), 3, 3000);
        validateReportText(input.conclusion(), 3, 3000);
        validate(input.reason(), 3, 1800);
        EvidenceSnapshot snapshot = snapshots.findById(input.evidenceSnapshotId())
                .orElseThrow(CasePreparationWriteService::notFound);
        if (!target.getTenantId().equals(snapshot.getTenantId())
                || !target.getEventId().equals(snapshot.getEventId())
                || !caseId.equals(snapshot.getCaseId())) throw notFound();
        if (snapshot.getEvidenceRevision() != target.getEvidenceRevision())
            throw conflict("EVIDENCE_VERSION_MISMATCH");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        manifests.recheckDatabase(target, sources.findByCaseIdOrderByLinkedAtAscIdAsc(caseId), prepared, now);
        if (!prepared.sha256().equals(snapshot.getManifestSha256())) throw conflict("SOURCE_CHANGED");
        Map<String, Object> content = new LinkedHashMap<>();
        ReportTemplateRegistry.Template template = templates.current();
        content.put("contentSchemaVersion", 1);
        content.put("templateVersion", template.version());
        content.put("evidenceSnapshotId", snapshot.getId().toString());
        content.put("manifestSha256", snapshot.getManifestSha256());
        content.put("title", input.title().trim());
        content.put("assessment", input.assessment().trim());
        content.put("limitations", input.limitations().trim());
        content.put("conclusion", input.conclusion().trim());
        String contentJson = json.writeValueAsString(content);
        if (contentJson.getBytes(StandardCharsets.UTF_8).length > 60000) throw invalid();
        String contentSha256 = CaseSnapshotManifest.sha256(contentJson);
        ReportVersion latest = drafts.findTopByCaseIdOrderByRevisionDesc(caseId).orElse(null);
        if (latest != null && latest.getContentSha256().equals(contentSha256))
            throw conflict("REPORT_CONTENT_UNCHANGED");
        long revision = latest == null ? 1 : Math.addExact(latest.getRevision(), 1);
        long before = target.getCaseVersion();
        target.recordPreparation(now);
        cases.saveAndFlush(target);
        ReportVersion draft = drafts.saveAndFlush(new ReportVersion(target, snapshot, revision,
                template.version(), template.sha256(), contentSha256, contentJson, memberId, now));
        activities.saveAndFlush(new CaseActivity(target, memberId, AdminRole.REVIEWER.name(),
                "REPORT_DRAFT_CREATED", "reportVersion=" + draft.getId() + "; " + input.reason().trim(),
                target.getStatus(), target.getStatus(), before, target.getCaseVersion(), null, now));
        CasePreparationDto.Draft response = CasePreparationDto.Draft.from(draft);
        idempotency.saveAndFlush(new CaseIdempotencyRecord(target.getTenantId(), memberId, "DRAFT_CREATE",
                caseId, keyDigest, requestDigest, caseId,
                json.writeValueAsString(Map.of("reportVersionId", draft.getId())), now));
        return response;
    }

    private Case reviewerCase(UUID caseId, UUID memberId, Long expected) {
        Case target = cases.findById(caseId).orElseThrow(CasePreparationWriteService::notFound);
        var actor = access.requireResourceAccess(
                new AdminResourceScope(target.getTenantId(), target.getEventId()), null, REVIEWER);
        if (!memberships.actor(actor).getId().equals(memberId)) throw notFound();
        if (expected == null) throw invalid();
        if (!Objects.equals(expected, target.getCaseVersion())) throw conflict("CASE_VERSION_CONFLICT");
        return target;
    }

    static void validate(String value, int min, int max) {
        if (value == null || value.trim().length() < min || value.length() > max) throw invalid();
    }

    /**
     * 공식 PDF XHTML에 그대로 들어가는 보고서 본문용 검증이다. 봉인 뒤에는 원문을 고칠 수 없으므로
     * XML 1.0에서 허용하지 않는 문자와 짝이 맞지 않는 surrogate는 삭제나 치환 없이 작성 시점에 거부한다.
     */
    static void validateReportText(String value, int min, int max) {
        validate(value, min, max);
        if (!value.codePoints().allMatch(CasePreparationWriteService::isXml10Char)) throw invalid();
    }

    private static boolean isXml10Char(int c) {
        // 짝이 없는 surrogate는 codePoints()에서 0xD800~0xDFFF 값으로 나오므로 여기서 함께 걸러진다.
        return c == 0x9 || c == 0xA || c == 0xD || (c >= 0x20 && c <= 0xD7FF)
                || (c >= 0xE000 && c <= 0xFFFD) || (c >= 0x10000 && c <= 0x10FFFF);
    }

    private static CaseApiException invalid() {
        return new CaseApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED");
    }

    private static CaseApiException notFound() {
        return new CaseApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }

    private static CaseApiException conflict(String code) {
        return new CaseApiException(HttpStatus.CONFLICT, code);
    }
}
