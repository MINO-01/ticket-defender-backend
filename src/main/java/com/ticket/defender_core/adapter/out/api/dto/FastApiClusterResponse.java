package com.ticket.defender_core.adapter.out.api.dto;

import java.util.List;

/** 군집과 그 군집에 포함된 티켓의 연결 관계를 보존하는 응답 DTO입니다. */
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
        members = members == null ? List.of() : List.copyOf(members);
        paymentHashes = paymentHashes == null ? List.of() : List.copyOf(paymentHashes);
        addressHashes = addressHashes == null ? List.of() : List.copyOf(addressHashes);
        deviceIdHashes = deviceIdHashes == null ? List.of() : List.copyOf(deviceIdHashes);
        ipHashes = ipHashes == null ? List.of() : List.copyOf(ipHashes);
    }

    public record Member(
            String accountId,
            String reservationNo,
            String eventId
    ) {}
}
