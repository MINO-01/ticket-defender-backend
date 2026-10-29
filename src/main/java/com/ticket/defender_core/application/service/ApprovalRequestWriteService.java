package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.ApprovalRequestDto;
import com.ticket.defender_core.adapter.out.persistence.ApprovalAuditEventRepository;
import com.ticket.defender_core.adapter.out.persistence.ApprovalRequestRepository;
import com.ticket.defender_core.adapter.out.persistence.CaseActivityRepository;
import com.ticket.defender_core.adapter.out.persistence.CaseIdempotencyRepository;
import com.ticket.defender_core.adapter.out.persistence.CaseRepository;
import com.ticket.defender_core.adapter.out.persistence.EvidenceSnapshotRepository;
import com.ticket.defender_core.adapter.out.persistence.IssuanceHoldRepository;
import com.ticket.defender_core.adapter.out.persistence.ReportVersionRepository;
import com.ticket.defender_core.domain.ApprovalAuditEvent;
import com.ticket.defender_core.domain.ApprovalRequest;
import com.ticket.defender_core.domain.Case;
import com.ticket.defender_core.domain.CaseActivity;
import com.ticket.defender_core.domain.CaseIdempotencyRecord;
import com.ticket.defender_core.domain.EvidenceSnapshot;
import com.ticket.defender_core.domain.IssuanceHold;
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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ApprovalRequestWriteService {
    private static final Set<AdminRole> REVIEWER = Set.of(AdminRole.REVIEWER);

    private final AdminAccessService access;
    private final CaseMembershipAccess memberships;
    private final CaseRepository cases;
    private final EvidenceSnapshotRepository snapshots;
    private final ReportVersionRepository reports;
    private final ApprovalRequestRepository requests;
    private final IssuanceHoldRepository holds;
    private final ApprovalRequestBasisValidator basis;
    private final ReportTemplateRegistry templates;
    private final CaseActivityRepository activities;
    private final ApprovalAuditEventRepository audits;
    private final CaseIdempotencyRepository idempotency;
    private final ObjectMapper json;

    @Transactional
    public ApprovalRequest submit(UUID caseId, ApprovalRequestDto.Submit input, UUID memberId,
            String keyDigest, String requestDigest) {
        Case target = cases.findByIdForUpdate(caseId).orElseThrow(ApprovalRequestWriteService::notFound);
        var actor = access.requireResourceAccess(
                new AdminResourceScope(target.getTenantId(), target.getEventId()), null, REVIEWER);
        if (!memberships.actor(actor).getId().equals(memberId)) throw notFound();
        if (requests.findByTenantIdAndCaseIdAndStatus(
                target.getTenantId(), caseId, ApprovalRequest.Status.PENDING).isPresent())
            throw conflict("APPROVAL_REQUEST_ALREADY_PENDING");
        if (holds.existsByTenantIdAndEventIdAndCaseIdAndStatus(target.getTenantId(), target.getEventId(),
                caseId, IssuanceHold.Status.ACTIVE)) throw conflict("ISSUANCE_HOLD_ACTIVE");
        if (!Objects.equals(input.expectedCaseVersion(), target.getCaseVersion()))
            throw conflict("CASE_VERSION_CONFLICT");
        if (target.getStatus() != Case.Status.IN_REVIEW) throw conflict("CASE_STATE_CONFLICT");

        ReportVersion report = reports.findById(input.reportVersionId())
                .orElseThrow(ApprovalRequestWriteService::notFound);
        EvidenceSnapshot snapshot = snapshots.findById(input.evidenceSnapshotId())
                .orElseThrow(ApprovalRequestWriteService::notFound);
        requireScope(target, report, snapshot);
        if (requests.findByReportVersionId(report.getId()).isPresent())
            throw conflict("REPORT_REVISION_CLOSED");
        if (!Objects.equals(input.expectedReportVersion(), report.getVersion()))
            throw conflict("REPORT_VERSION_CONFLICT");
        if (!ReportVersion.Status.DRAFT.name().equals(report.getStatus()))
            throw conflict("REPORT_REVISION_CLOSED");
        ReportVersion latest = reports.findTopByCaseIdOrderByRevisionDesc(caseId)
                .orElseThrow(ApprovalRequestWriteService::notFound);
        if (!latest.getId().equals(report.getId())) throw conflict("REPORT_VERSION_CONFLICT");
        if (target.getEvidenceRevision() != snapshot.getEvidenceRevision()
                || report.getEvidenceRevision() != snapshot.getEvidenceRevision())
            throw conflict("EVIDENCE_VERSION_MISMATCH");
        if (!CaseSnapshotManifest.sha256(snapshot.getManifestJson()).equals(snapshot.getManifestSha256()))
            throw conflict("EVIDENCE_REVIEW_MISMATCH");
        if (!CaseSnapshotManifest.sha256(report.getContentJson()).equals(report.getContentSha256())
                || !report.getContentSha256().equals(input.expectedReportContentSha256())
                || !contentBinds(report, snapshot)) throw conflict("REPORT_CONTENT_MISMATCH");
        if (!templates.verifies(report.getTemplateVersion(), report.getTemplateSha256()))
            throw new CaseApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TEMPLATE_NOT_RETAINED");

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        basis.validate(target, snapshot, report, now);
        long before = target.getCaseVersion();
        target.submitForApproval(report.getId(), now);
        report.submitForApproval();
        cases.saveAndFlush(target);
        reports.saveAndFlush(report);

        ApprovalRequest request = requests.saveAndFlush(new ApprovalRequest(
                target, report, snapshot, memberId, input.reason().trim(), now));
        activities.saveAndFlush(new CaseActivity(target, memberId, AdminRole.REVIEWER.name(),
                "APPROVAL_REQUESTED", "approvalRequest=" + request.getId()
                        + "; reportVersion=" + report.getId() + "; " + input.reason().trim(),
                Case.Status.IN_REVIEW, Case.Status.READY_FOR_APPROVAL,
                before, target.getCaseVersion(), null, now));
        audits.saveAndFlush(new ApprovalAuditEvent(request, memberId, before, now));
        idempotency.saveAndFlush(new CaseIdempotencyRecord(target.getTenantId(), memberId,
                ApprovalRequestService.OPERATION, caseId, keyDigest, requestDigest, caseId,
                json.writeValueAsString(Map.of("approvalRequestId", request.getId())), now));
        return request;
    }

    @SuppressWarnings("unchecked")
    private boolean contentBinds(ReportVersion report, EvidenceSnapshot snapshot) {
        try {
            Map<String, Object> content = json.readValue(report.getContentJson(), Map.class);
            return Objects.equals(content.get("evidenceSnapshotId"), snapshot.getId().toString())
                    && Objects.equals(content.get("manifestSha256"), snapshot.getManifestSha256())
                    && Objects.equals(content.get("templateVersion"), report.getTemplateVersion());
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    private static void requireScope(Case target, ReportVersion report, EvidenceSnapshot snapshot) {
        if (!target.getTenantId().equals(report.getTenantId())
                || !target.getEventId().equals(report.getEventId())
                || !target.getId().equals(report.getCaseId())
                || !target.getTenantId().equals(snapshot.getTenantId())
                || !target.getEventId().equals(snapshot.getEventId())
                || !target.getId().equals(snapshot.getCaseId())
                || !snapshot.getId().equals(report.getEvidenceSnapshotId())) throw notFound();
    }

    private static CaseApiException notFound() {
        return new CaseApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }

    private static CaseApiException conflict(String code) {
        return new CaseApiException(HttpStatus.CONFLICT, code);
    }
}
