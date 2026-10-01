package com.ticket.defender_core.domain.event;

/**
 * 공식 암표 탐지 보고서(PDF)가 발급되어,
 * 제보자에게 포상금 안내 알림을 보내야 함을 알리는 도메인 이벤트
 */
public record FraudVerifiedEvent(
        Long auditId,
        String reservationNo,
        String reporterId
) {
}