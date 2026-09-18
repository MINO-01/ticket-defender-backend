package com.ticket.defender_core.domain;

public enum AuditStatus {
    NORMAL,                // 정상
    FRAUD_DETECTED,        // 암표 의심 군집으로 탐지됨
    REPORT_ISSUED      // 공식 소명 보고서 발급 완료 (관리자 조치 완료)
}
