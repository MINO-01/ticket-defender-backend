package com.ticket.defender_core.adapter.in.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 예매처가 전달하는 Track 1 티켓별 가명 분석 데이터입니다. */
public record AgentAnalysisRequest(
        @NotEmpty(message = "분석할 예매 내역이 한 건 이상 필요합니다.")
        List<@NotNull @Valid TicketData> tickets
) {

    @AssertTrue(message = "동일한 공연·예매·계정 조합은 한 번만 전달할 수 있습니다.")
    public boolean isTicketIdentityUnique() {
        if (tickets == null) {
            return true;
        }

        Set<TicketIdentity> identities = new HashSet<>();
        for (TicketData ticket : tickets) {
            if (ticket == null || ticket.accountId() == null || ticket.reservationNo() == null
                    || ticket.eventId() == null) {
                continue;
            }
            if (!identities.add(new TicketIdentity(ticket.accountId(), ticket.reservationNo(), ticket.eventId()))) {
                return false;
            }
        }
        return true;
    }

    public record TicketData(
            @NotBlank(message = "계정 식별자는 필수입니다.")
            @Size(max = 128, message = "계정 식별자는 128자 이하여야 합니다.")
            String accountId,

            @NotBlank(message = "예매 번호는 필수입니다.")
            @Size(max = 128, message = "예매 번호는 128자 이하여야 합니다.")
            String reservationNo,

            @NotBlank(message = "공연 식별자는 필수입니다.")
            @Size(max = 128, message = "공연 식별자는 128자 이하여야 합니다.")
            String eventId,

            @Pattern(regexp = "^[0-9a-f]{64}$", message = "결제 연결 토큰은 소문자 16진수 64자리여야 합니다.")
            String paymentHash,

            @Pattern(regexp = "^[0-9a-f]{64}$", message = "주소 연결 토큰은 소문자 16진수 64자리여야 합니다.")
            String addressHash,

            @Pattern(regexp = "^[0-9a-f]{64}$", message = "기기 연결 토큰은 소문자 16진수 64자리여야 합니다.")
            String deviceIdHash,

            @Pattern(regexp = "^[0-9a-f]{64}$", message = "IP 연결 토큰은 소문자 16진수 64자리여야 합니다.")
            String ipHash
    ) {
        @AssertTrue(message = "각 예매 내역에는 하나 이상의 가명 분석 신호가 필요합니다.")
        public boolean isHasAnySignalHash() {
            return hasValue(paymentHash) || hasValue(addressHash)
                    || hasValue(deviceIdHash) || hasValue(ipHash);
        }

        private static boolean hasValue(String value) {
            return value != null && !value.isBlank();
        }
    }

    private record TicketIdentity(String accountId, String reservationNo, String eventId) {}
}
