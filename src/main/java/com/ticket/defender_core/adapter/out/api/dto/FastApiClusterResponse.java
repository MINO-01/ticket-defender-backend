package com.ticket.defender_core.adapter.out.api.dto;

import java.util.List;

public record FastApiClusterResponse(
        String clusterId,
        List<String> fraudulentPaymentHashes,
        List<String> fraudulentAddressHashes,
        Double fraudProbability
) {}