package com.ticket.defender_core.adapter.out.api.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Trusted Spring-to-Data v2 payload.  Values in this type must originate from an
 * authoritative reservation ledger; it deliberately has no defaults for purchase
 * time, net quantity, or quantity-snapshot provenance.
 */
public record Track1PurchaseLimitAnalysisRequest(
        String requestId,
        List<PerformanceScope> performanceScopes,
        List<Ticket> tickets
) {
    public Track1PurchaseLimitAnalysisRequest {
        performanceScopes = performanceScopes == null ? null : List.copyOf(performanceScopes);
        tickets = tickets == null ? null : List.copyOf(tickets);
    }

    public record PerformanceScope(
            UUID internalEventId,
            String eventId,
            UUID performanceId,
            PolicyVerificationStatus policyVerificationStatus,
            ConfirmedPolicy policy,
            QuantityDataStatus quantityDataStatus,
            String quantitySourceVersion,
            OffsetDateTime quantityAsOf,
            CancellationHandling cancellationHandling,
            DuplicateHandling duplicateHandling,
            String dataCompletenessReason
    ) { }

    public record ConfirmedPolicy(
            int maxTicketsPerAccount,
            String policyVersion,
            OffsetDateTime effectiveFrom,
            OffsetDateTime effectiveUntil
    ) { }

    public record Ticket(
            String accountId,
            String reservationNo,
            String eventId,
            UUID performanceId,
            OffsetDateTime purchasedAt,
            int effectiveTicketQuantity,
            String paymentHash,
            String addressHash,
            String deviceIdHash,
            String ipHash
    ) { }

    public enum PolicyVerificationStatus { CONFIRMED, POLICY_NOT_CONFIRMED }
    public enum QuantityDataStatus { COMPLETE, INCOMPLETE, UNKNOWN }
    public enum CancellationHandling { NETTED, NOT_CONFIRMED }
    public enum DuplicateHandling { DEDUPLICATED, NOT_CONFIRMED }
}
