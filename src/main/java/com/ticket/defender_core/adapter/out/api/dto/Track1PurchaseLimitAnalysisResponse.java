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
        List<PurchaseLimitEvaluation> purchaseLimitEvaluations,
        List<SharedSignalObservation> sharedSignalObservations
) {
    public Track1PurchaseLimitAnalysisResponse {
        sharedSignalObservations = sharedSignalObservations == null ? List.of() : List.copyOf(sharedSignalObservations);
    }
    public Track1PurchaseLimitAnalysisResponse(String requestId, Status status, String algorithm, String algorithmVersion,
            OffsetDateTime analyzedAt, List<FastApiClusterResponse> clusters,
            List<PurchaseLimitEvaluation> purchaseLimitEvaluations) {
        this(requestId, status, algorithm, algorithmVersion, analyzedAt, clusters, purchaseLimitEvaluations, List.of());
    }
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

    public enum SignalType { IP, ADDRESS }
    public enum TicketQuantityStatus { VERIFIED, UNAVAILABLE }

    public record Corroboration(int paymentSharedAccountCount, int deviceSharedAccountCount) { }

    /** Aggregated signal only: raw IP/address values and their HMACs are intentionally absent. */
    public record SharedSignalObservation(String observationId, String scopeType, String scopeId, SignalType signalType,
                                          int distinctAccountCount, int scopeAccountCount, int distinctReservationCount,
                                          Integer totalTicketQuantity, TicketQuantityStatus ticketQuantityStatus,
                                          Corroboration corroboration, String ipNetworkType,
                                          Integer purchaseWindowSeconds, boolean excludedFromLouvain) { }
}
