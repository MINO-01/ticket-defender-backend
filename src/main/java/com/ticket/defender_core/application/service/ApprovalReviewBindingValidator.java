package com.ticket.defender_core.application.service;

import com.ticket.defender_core.domain.ApprovalRequest;
import com.ticket.defender_core.domain.Case;
import com.ticket.defender_core.domain.EvidenceSnapshot;
import com.ticket.defender_core.domain.ReportVersion;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Objects;

/** Revalidates only the immutable package bindings held by a pending approval request. */
@Component
@RequiredArgsConstructor
public class ApprovalReviewBindingValidator {
    private final ReportTemplateRegistry templates;
    private final ObjectMapper json;

    public void validatePending(Case target, ApprovalRequest request, EvidenceSnapshot snapshot,
            ReportVersion report) {
        validatePendingLifecycle(target, request, snapshot, report);
        if (target.getEvidenceRevision() != request.getEvidenceRevision()
                || snapshot.getEvidenceRevision() != request.getEvidenceRevision()
                || report.getEvidenceRevision() != request.getEvidenceRevision()
                || report.getRevision() != request.getReportRevision()) {
            throw conflict("EVIDENCE_VERSION_MISMATCH");
        }
        if (!request.getManifestSha256().equals(snapshot.getManifestSha256())
                || !request.getReportContentSha256().equals(report.getContentSha256())
                || !request.getReportTemplateVersion().equals(report.getTemplateVersion())
                || !request.getReportTemplateSha256().equals(report.getTemplateSha256())) {
            throw conflict("APPROVAL_REVIEW_MISMATCH");
        }
        if (!CaseSnapshotManifest.sha256(snapshot.getManifestJson()).equals(snapshot.getManifestSha256())) {
            throw conflict("EVIDENCE_REVIEW_MISMATCH");
        }
        if (!CaseSnapshotManifest.sha256(report.getContentJson()).equals(report.getContentSha256())
                || !contentBinds(report, snapshot)) {
            throw conflict("REPORT_CONTENT_MISMATCH");
        }
        if (!templates.verifies(report.getTemplateVersion(), report.getTemplateSha256())) {
            throw new CaseApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TEMPLATE_NOT_RETAINED");
        }
    }

    /** Validates only the current request lifecycle and ownership before trusted invalidation. */
    public void validatePendingLifecycle(Case target, ApprovalRequest request, EvidenceSnapshot snapshot,
            ReportVersion report) {
        requireScope(target, request, snapshot, report);
        if (request.getStatus() != ApprovalRequest.Status.PENDING
                || target.getStatus() != Case.Status.READY_FOR_APPROVAL
                || !Objects.equals(target.getCurrentReportVersionId(), report.getId())
                || !ReportVersion.Status.PENDING_APPROVAL.name().equals(report.getStatus())) {
            throw conflict("APPROVAL_REQUEST_CLOSED");
        }
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

    private static void requireScope(Case target, ApprovalRequest request, EvidenceSnapshot snapshot,
            ReportVersion report) {
        if (!target.getTenantId().equals(request.getTenantId())
                || !target.getEventId().equals(request.getEventId())
                || !target.getId().equals(request.getCaseId())
                || !target.getTenantId().equals(snapshot.getTenantId())
                || !target.getEventId().equals(snapshot.getEventId())
                || !target.getId().equals(snapshot.getCaseId())
                || !target.getTenantId().equals(report.getTenantId())
                || !target.getEventId().equals(report.getEventId())
                || !target.getId().equals(report.getCaseId())
                || !request.getEvidenceSnapshotId().equals(snapshot.getId())
                || !request.getReportVersionId().equals(report.getId())
                || !report.getEvidenceSnapshotId().equals(snapshot.getId())) {
            throw new CaseApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        }
    }

    private static CaseApiException conflict(String code) {
        return new CaseApiException(HttpStatus.CONFLICT, code);
    }
}
