package com.ticket.defender_core.adapter.out.api.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** 정상 분석 결과와 Spring 내부의 분석 불가 상태를 함께 표현합니다. */
public record MacroAnalysisResponse(
        String requestId,
        Status status,
        String algorithm,
        String algorithmVersion,
        OffsetDateTime analyzedAt,
        List<FastApiClusterResponse> clusters
) {
    public enum Status {
        COMPLETED,
        UNAVAILABLE
    }

    public static MacroAnalysisResponse unavailable(String requestId) {
        return new MacroAnalysisResponse(
                requestId,
                Status.UNAVAILABLE,
                null,
                null,
                null,
                List.of()
        );
    }
}
