package com.ticket.defender_core.adapter.out.api.dto;

import java.util.List;

public record FastApiClusterResponse(
        String status,
        String message,
        List<ClusterData> data
) {
    public record ClusterData(
            String payment_hash,
            int account_count,
            List<String> accounts
    ) {
        public String getMaskedPaymentHash() {
            if (payment_hash == null || payment_hash.length() < 8) return "***";
            return payment_hash.substring(0, 8) + "***";
        }
    }
}