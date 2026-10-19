package com.ticket.defender_core.adapter.in.web.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class Track1ClusterDto {
    private Track1ClusterDto() {
    }

    public record Event(UUID id, String externalEventId, String name) {
    }

    public record Summary(
            String analysisRequestId,
            String clusterId,
            Double riskScore,
            String analysisAlgorithm,
            String analysisAlgorithmVersion,
            OffsetDateTime analyzedAt,
            long storedMemberCount
    ) {
    }

    public record Member(Long auditId, String accountId, String reservationNo) {
    }

    public record Page(
            Event event,
            List<Summary> items,
            int page,
            int pageSize,
            long totalItems,
            boolean hasNext,
            String coverage
    ) {
    }

    public record Detail(Event event, Summary cluster, List<Member> members, String coverage) {
    }
}
