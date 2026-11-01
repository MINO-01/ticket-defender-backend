package com.ticket.defender_core.adapter.in.web.dto;

import java.util.List;
import java.util.UUID;

/**
 * 관리자 접근 가능 공연 목록 응답입니다. 공연 식별자와 표시 정보만 담으며
 * tenantId, 고객사 이름, 멤버십, 역할, 공연 상태 같은 내부 권한 정보는 포함하지 않습니다.
 */
public final class AdminEventDto {
    private AdminEventDto() {
    }

    /** id는 Track 1, Track 2, Case API의 eventId로 그대로 사용합니다. */
    public record Event(UUID id, String externalEventId, String name) {
    }

    public record Page(List<Event> items, int page, int pageSize, long totalItems, boolean hasNext) {
    }
}
