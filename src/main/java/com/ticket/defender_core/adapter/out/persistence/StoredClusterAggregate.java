package com.ticket.defender_core.adapter.out.persistence;

import java.time.LocalDateTime;

public record StoredClusterAggregate(
        String analysisRequestId,
        String clusterId,
        Double minimumRiskScore,
        Double maximumRiskScore,
        String firstAlgorithm,
        String lastAlgorithm,
        String firstAlgorithmVersion,
        String lastAlgorithmVersion,
        LocalDateTime firstAnalyzedAt,
        LocalDateTime lastAnalyzedAt,
        Long storedMemberCount
) {
}
