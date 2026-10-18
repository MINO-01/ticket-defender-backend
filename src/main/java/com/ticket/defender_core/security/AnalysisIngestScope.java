package com.ticket.defender_core.security;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record AnalysisIngestScope(UUID tenantId, Map<String, UUID> eventIds) {
    public AnalysisIngestScope {
        Objects.requireNonNull(tenantId);
        eventIds = Map.copyOf(eventIds);
    }

    public UUID requireEventId(String externalEventRef) {
        UUID eventId = eventIds.get(externalEventRef);
        if (eventId == null) {
            throw new IllegalStateException("검증되지 않은 공연 식별자는 저장할 수 없습니다.");
        }
        return eventId;
    }
}
