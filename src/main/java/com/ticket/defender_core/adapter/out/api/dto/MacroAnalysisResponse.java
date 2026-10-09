package com.ticket.defender_core.adapter.out.api.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** 분석 완료 결과와 분석 불가 상태를 담습니다. */
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

    /** 분석 결과를 사용할 수 없을 때 반환할 응답을 만듭니다. */
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
