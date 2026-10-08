package com.ticket.defender_core.domain;

public enum AuditStatus {
    NORMAL,                // 정상
    FRAUD_DETECTED,        // 암표 의심 군집으로 탐지됨
    REPORT_ISSUED,         // 공식 암표 탐지 보고서 발급 완료 (관리자 조치 완료)
    DUPLICATED             // 동일 예매 건에 대해 다른 제보자가 먼저 승인되어 중복 처리됨
}
