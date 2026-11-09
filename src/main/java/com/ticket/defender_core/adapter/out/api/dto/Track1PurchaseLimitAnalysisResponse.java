package com.ticket.defender_core.adapter.out.api.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Response contract for Data Issue #30's isolated Track 1 v2 endpoint. */
public record Track1PurchaseLimitAnalysisResponse(
        String requestId,
        Status status,
        String algorithm,
        String algorithmVersion,
        OffsetDateTime analyzedAt,
        List<FastApiClusterResponse> clusters,
        List<PurchaseLimitEvaluation> purchaseLimitEvaluations
) {
    public enum Status { COMPLETED }
    public enum EvaluationStatus {
        EVALUATED, POLICY_NOT_CONFIRMED, QUANTITY_DATA_INCOMPLETE, POLICY_TIME_NOT_APPLICABLE
    }

    public record PurchaseLimitEvaluation(
            UUID internalEventId,
            String eventId,
            UUID performanceId,
            EvaluationStatus status,
            String policyVersion,
            List<AccountLimitFinding> accountLimitFindings
    ) { }

    public record AccountLimitFinding(
            String accountId,
            int effectiveTicketQuantity,
            int maxTicketsPerAccount,
            String policyVersion,
            List<ReservationQuantity> reservationQuantities
    ) { }

    public record ReservationQuantity(String reservationNo, int effectiveTicketQuantity) { }
}
