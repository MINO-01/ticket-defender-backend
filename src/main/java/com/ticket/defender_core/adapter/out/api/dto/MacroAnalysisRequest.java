package com.ticket.defender_core.adapter.out.api.dto;

import java.util.List;

public record MacroAnalysisRequest(
        List<String> paymentHashes,
        List<String> addressHashes
) {}
