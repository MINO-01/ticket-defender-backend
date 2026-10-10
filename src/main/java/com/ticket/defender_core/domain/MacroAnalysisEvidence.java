package com.ticket.defender_core.domain;

import java.time.LocalDateTime;
import java.util.Objects;

/** 예매 건의 가명 근거와 군집 분석 출처를 담습니다. */
public record MacroAnalysisEvidence(
        String accountId,
        String reservationNo,
        String eventId,
        String paymentHash,
        String addressHash,
        String deviceIdHash,
        String ipHash,
        String clusterId,
        Double riskScore,
        String algorithm,
        String algorithmVersion,
        String analysisRequestId,
        LocalDateTime analyzedAt
) {
    /** 저장에 필요한 식별자와 분석 정보를 확인합니다. */
    public MacroAnalysisEvidence {
        Objects.requireNonNull(accountId, "계정 식별자는 필수입니다.");
        Objects.requireNonNull(reservationNo, "예매 번호는 필수입니다.");
        Objects.requireNonNull(eventId, "공연 식별자는 필수입니다.");
        Objects.requireNonNull(clusterId, "군집 식별자는 필수입니다.");
        Objects.requireNonNull(riskScore, "군집 위험도는 필수입니다.");
        Objects.requireNonNull(algorithm, "분석 알고리즘은 필수입니다.");
        Objects.requireNonNull(algorithmVersion, "분석 알고리즘 버전은 필수입니다.");
        Objects.requireNonNull(analysisRequestId, "분석 요청 ID는 필수입니다.");
        Objects.requireNonNull(analyzedAt, "분석 완료 시각은 필수입니다.");

        if (!Double.isFinite(riskScore) || riskScore < 0 || riskScore > 1) {
            throw new IllegalArgumentException("군집 위험도는 0부터 1 사이여야 합니다.");
        }
        if (isBlank(paymentHash) && isBlank(addressHash) && isBlank(deviceIdHash) && isBlank(ipHash)) {
            throw new IllegalArgumentException("가명 분석 연결 신호가 하나 이상 필요합니다.");
        }
    }

    /** 값이 null이나 공백인지 확인합니다. */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
