package com.ticket.defender_core.adapter.out.api.dto;

import java.util.List;

/** FastAPI 군집 분석 결과의 군집 항목입니다. */
public record FastApiClusterResponse(
        String clusterId,
        List<Member> members,
        List<String> paymentHashes,
        List<String> addressHashes,
        List<String> deviceIdHashes,
        List<String> ipHashes,
        Double riskScore
) {
    public FastApiClusterResponse {
        members = members == null ? null : List.copyOf(members);
        paymentHashes = paymentHashes == null ? null : List.copyOf(paymentHashes);
        addressHashes = addressHashes == null ? null : List.copyOf(addressHashes);
        deviceIdHashes = deviceIdHashes == null ? null : List.copyOf(deviceIdHashes);
        ipHashes = ipHashes == null ? null : List.copyOf(ipHashes);
    }

    public record Member(
            String accountId,
            String reservationNo,
            String eventId
    ) {}
}
