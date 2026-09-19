package com.ticket.defender_core.adapter.in.web.dto;

import java.util.List;

public record AgentAnalysisRequest( List<TicketHashData> tickets) {
    public record TicketHashData(
            String accountId,
            String paymentHash,
            String addressHash
    ) {}
}
