package com.ticket.defender_core.application.service;

/** 담당자 승인 절차가 마련되기 전의 공식 감사 보고서 발급 요청을 거부합니다. */
public class AuditReportIssuanceDisabledException extends RuntimeException {

    public static final String ERROR_CODE = "AUDIT_REPORT_ISSUANCE_DISABLED";

    public AuditReportIssuanceDisabledException() {
        super("감사 보고서 발급 기능은 승인 절차 구축 전까지 일시적으로 제공되지 않습니다.");
    }
}
