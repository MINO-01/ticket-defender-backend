package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.ApprovalAuditDto;
import com.ticket.defender_core.adapter.in.web.dto.CaseDto;
import com.ticket.defender_core.adapter.in.web.dto.CaseWorkflowDto;
import com.ticket.defender_core.adapter.out.persistence.ApprovalAuditEventRepository;
import com.ticket.defender_core.adapter.out.persistence.ApprovalDecisionRepository;
import com.ticket.defender_core.adapter.out.persistence.ApprovalRequestRepository;
import com.ticket.defender_core.adapter.out.persistence.CaseRepository;
import com.ticket.defender_core.adapter.out.persistence.OfficialPdfIssuanceRepository;
import com.ticket.defender_core.adapter.out.persistence.ReportVersionRepository;
import com.ticket.defender_core.domain.ApprovalDecision;
import com.ticket.defender_core.domain.ApprovalRequest;
import com.ticket.defender_core.domain.Case;
import com.ticket.defender_core.domain.OfficialPdfIssuance;
import com.ticket.defender_core.domain.ReportVersion;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

/** Uses existing immutable records to show linkage; it never determines PDF eligibility from Case status alone. */
@Service
@RequiredArgsConstructor
public class CaseWorkflowQueryService {
    private final CaseService cases;
    private final CaseRepository caseRepository;
    private final ReportVersionRepository reports;
    private final ApprovalRequestRepository requests;
    private final ApprovalDecisionRepository decisions;
    private final OfficialPdfIssuanceRepository issuances;
    private final ApprovalAuditEventRepository approvalAudits;

    @Transactional(readOnly = true)
    public CaseWorkflowDto.View workflow(UUID caseId) {
        CaseDto.View visible = cases.detail(caseId);
        Case target = caseRepository.findById(caseId).orElseThrow(CaseWorkflowQueryService::notFound);
        ReportVersion report = report(target);
        ApprovalRequest request = report == null ? null : requests.findByReportVersionId(report.getId()).orElse(null);
        ApprovalDecision decision = request == null ? null : decisions.findByApprovalRequestId(request.getId()).orElse(null);
        OfficialPdfIssuance issuance = decision == null ? null : issuances.findByApprovalDecisionId(decision.getId()).orElse(null);
        return new CaseWorkflowDto.View(visible, next(target, request, decision, issuance), report(report),
                approval(request, decision), officialPdf(issuance));
    }

    @Transactional(readOnly = true)
    public ApprovalAuditDto.Page approvalAudit(UUID caseId, int page, int pageSize) {
        if (page < 0 || pageSize < 1 || pageSize > 100 || (long) page * pageSize > Integer.MAX_VALUE)
            throw new CaseApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        cases.detail(caseId);
        var found = approvalAudits.findByCaseId(caseId,
                PageRequest.of(page, pageSize, Sort.by("occurredAt").ascending().and(Sort.by("id").ascending())));
        return new ApprovalAuditDto.Page(found.stream().map(ApprovalAuditDto.Item::from).toList(),
                page, pageSize, found.hasNext());
    }

    private ReportVersion report(Case target) {
        UUID current = target.getCurrentReportVersionId();
        ReportVersion report = current == null ? reports.findTopByCaseIdOrderByRevisionDesc(target.getId()).orElse(null)
                : reports.findById(current).orElseThrow(CaseWorkflowQueryService::notFound);
        if (report != null && (!Objects.equals(target.getId(), report.getCaseId())
                || !Objects.equals(target.getTenantId(), report.getTenantId())
                || !Objects.equals(target.getEventId(), report.getEventId()))) throw notFound();
        return report;
    }

    private static CaseWorkflowDto.Report report(ReportVersion value) {
        return value == null ? null : new CaseWorkflowDto.Report(value.getId(), value.getEvidenceSnapshotId(),
                value.getEvidenceRevision(), value.getRevision(), value.getVersion(),
                ReportVersion.Status.valueOf(value.getStatus()));
    }

    private static CaseWorkflowDto.Approval approval(ApprovalRequest request, ApprovalDecision decision) {
        return request == null ? null : new CaseWorkflowDto.Approval(request.getId(), request.getStatus(),
                decision == null ? null : decision.getId(), decision == null ? null : decision.getOutcome(),
                decision == null ? null : decision.getDecidedAt().atOffset(ZoneOffset.UTC));
    }

    private static CaseWorkflowDto.OfficialPdf officialPdf(OfficialPdfIssuance value) {
        return value == null ? null : new CaseWorkflowDto.OfficialPdf(value.getId(), value.getStatus(),
                value.getLastFailureCode());
    }

    private static CaseWorkflowDto.NextStep next(Case target, ApprovalRequest request,
            ApprovalDecision decision, OfficialPdfIssuance issuance) {
        return switch (target.getStatus()) {
            case OPEN -> step("TRIAGE_REQUIRED", "REVIEWER가 분류와 담당을 기록해 TRIAGED로 전환합니다.");
            case TRIAGED -> step("REVIEW_START_REQUIRED", "담당 REVIEWER가 IN_REVIEW 전환을 기록합니다.");
            case IN_REVIEW -> step("EVIDENCE_AND_REPORT_PREPARATION", "현재 근거 snapshot과 보고서 초안을 준비하거나 추가 근거 필요 상태를 기록합니다.");
            case NEEDS_EVIDENCE -> step("EVIDENCE_GAP_REVIEW", "요청한 근거의 확보 여부를 검토한 뒤 검토 상태를 갱신합니다.");
            case READY_FOR_APPROVAL -> request == null ? step("APPROVAL_BINDING_MISSING", "승인 요청 바인딩을 확인해야 합니다.")
                    : step("INDEPENDENT_APPROVER_DECISION_REQUIRED", "요청자와 다른 APPROVER의 결정이 필요합니다.");
            case APPROVED_FOR_REPORT -> issuance == null ? step("OFFICIAL_PDF_POLICY_GATE", "승인만으로 발급되지 않으며 현재 적격성과 공식 PDF 운영 게이트를 별도로 확인합니다.")
                    : step("OFFICIAL_PDF_RECORD_EXISTS", "기록된 발급 상태와 적격성 코드를 확인합니다.");
            case DISMISSED -> step("CASE_CLOSURE_REVIEW", "종결 또는 중요한 새 근거에 대한 독립 후속 Case를 검토합니다.");
            case CLOSED -> step("CASE_CLOSED", "새 근거는 기존 사건 재개가 아니라 연결된 후속 Case로 처리합니다.");
        };
    }

    private static CaseWorkflowDto.NextStep step(String code, String description) {
        return new CaseWorkflowDto.NextStep(code, description);
    }

    private static CaseApiException notFound() {
        return new CaseApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }
}
