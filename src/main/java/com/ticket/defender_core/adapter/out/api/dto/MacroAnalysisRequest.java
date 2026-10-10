package com.ticket.defender_core.adapter.out.api.dto;

import java.util.List;

/** FastAPI Track 1 분석 요청 계약입니다. */
public record MacroAnalysisRequest(
        String requestId,
        List<Ticket> tickets
) {
    public record Ticket(
            String accountId,
            String reservationNo,
            String eventId,
            String paymentHash,
            String addressHash,
            String deviceIdHash,
            String ipHash
    ) {}
}
