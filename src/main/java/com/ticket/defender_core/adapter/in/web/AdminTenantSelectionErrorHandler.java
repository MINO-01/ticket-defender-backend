package com.ticket.defender_core.adapter.in.web;

import com.ticket.defender_core.security.TenantSelectionRequiredException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 다중 고객사 관리자 임시 차단(DEC-02 확정 전)을 모든 관리자 업무 컨트롤러에서 같은 409 응답으로 변환합니다.
 * Track 1 컨트롤러처럼 CaseErrorHandler 범위 밖인 컨트롤러도 포함하며, 이 예외 외의 오류 처리는 바꾸지 않습니다.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {AdminTrack1ClusterController.class, AdminFanReportController.class,
        AdminAnalysisRunController.class, AdminOperationSettingsController.class,
        ListingContextAnalysisController.class, CaseController.class, CasePreparationController.class,
        ReportDraftController.class, EvidenceIntegrityController.class, ApprovalRequestController.class,
        OfficialPdfIssuanceController.class, AdminEventController.class, Track1QuantityController.class, AdminConsoleController.class})
public class AdminTenantSelectionErrorHandler {
    @ExceptionHandler(TenantSelectionRequiredException.class)
    ResponseEntity<ProblemDetail> tenantSelectionRequired(TenantSelectionRequiredException ignored) {
        return CaseErrorHandler.problem(HttpStatus.CONFLICT, "TENANT_SELECTION_REQUIRED");
    }
}
