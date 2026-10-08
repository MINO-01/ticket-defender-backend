package com.ticket.defender_core.domain.event;

/**
 * 동일 예매 건에 대한 선순위 제보자가 존재하여,
 * 현재 제보가 중복으로 반려되었음을 알리는 도메인 이벤트
 */
public record DuplicateReportEvent(
        Long auditId,
        String reservationNo,
        String reporterId
) {
}
