package com.ticket.defender_core.adapter.out.api;

import com.ticket.defender_core.adapter.out.api.dto.Track1PurchaseLimitAnalysisRequest;
import com.ticket.defender_core.adapter.out.api.dto.Track1PurchaseLimitAnalysisResponse;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Strict client-side boundary for the Data #30 v2 contract. */
final class Track1PurchaseLimitContractValidator {
    private static final Pattern HASH = Pattern.compile("^[0-9a-f]{64}$");

    private Track1PurchaseLimitContractValidator() { }

    static void request(Track1PurchaseLimitAnalysisRequest request) {
        if (request == null || blank(request.requestId()) || request.performanceScopes() == null
                || request.performanceScopes().isEmpty() || request.tickets() == null || request.tickets().isEmpty()) {
            throw invalid("v2 request requires requestId, scopes, and ledger-backed tickets");
        }
        Map<UUID, Track1PurchaseLimitAnalysisRequest.PerformanceScope> scopes = new HashMap<>();
        Set<ScopeKey> uniqueScopes = new HashSet<>();
        for (Track1PurchaseLimitAnalysisRequest.PerformanceScope scope : request.performanceScopes()) {
            if (scope == null || scope.internalEventId() == null || scope.performanceId() == null || blank(scope.eventId())
                    || scope.policyVerificationStatus() == null || scope.quantityDataStatus() == null
                    || scope.cancellationHandling() == null || scope.duplicateHandling() == null) {
                throw invalid("v2 performance scope is incomplete");
            }
            if (!uniqueScopes.add(ScopeKey.of(scope)) || scopes.putIfAbsent(scope.performanceId(), scope) != null) {
                throw invalid("v2 performance scope is duplicated");
            }
            boolean confirmed = scope.policyVerificationStatus()
                    == Track1PurchaseLimitAnalysisRequest.PolicyVerificationStatus.CONFIRMED;
            if (confirmed != (scope.policy() != null)) throw invalid("policy confirmation and policy differ");
            if (confirmed) policy(scope.policy());
            if (scope.quantityDataStatus() == Track1PurchaseLimitAnalysisRequest.QuantityDataStatus.COMPLETE
                    && (blank(scope.quantitySourceVersion()) || scope.quantityAsOf() == null
                    || scope.cancellationHandling() != Track1PurchaseLimitAnalysisRequest.CancellationHandling.NETTED
                    || scope.duplicateHandling() != Track1PurchaseLimitAnalysisRequest.DuplicateHandling.DEDUPLICATED)) {
                throw invalid("COMPLETE quantity requires netted, deduplicated source provenance");
            }
        }
        Set<TicketKey> tickets = new HashSet<>();
        for (Track1PurchaseLimitAnalysisRequest.Ticket ticket : request.tickets()) {
            if (ticket == null || blank(ticket.accountId()) || blank(ticket.reservationNo()) || blank(ticket.eventId())
                    || ticket.performanceId() == null || ticket.purchasedAt() == null || ticket.effectiveTicketQuantity() < 1) {
                throw invalid("v2 ticket is missing authoritative purchase fields");
            }
            Track1PurchaseLimitAnalysisRequest.PerformanceScope scope = scopes.get(ticket.performanceId());
            if (scope == null || !scope.eventId().equals(ticket.eventId()) || !tickets.add(TicketKey.of(ticket))) {
                throw invalid("v2 ticket scope does not match exactly one performance");
            }
            tokens(ticket.paymentHash(), ticket.addressHash(), ticket.deviceIdHash(), ticket.ipHash());
            ticketProvenance(ticket);
        }
    }

    static void response(Track1PurchaseLimitAnalysisRequest request, Track1PurchaseLimitAnalysisResponse response) {
        if (response == null || !request.requestId().equals(response.requestId())
                || response.status() != Track1PurchaseLimitAnalysisResponse.Status.COMPLETED
                || !"LOUVAIN".equals(response.algorithm()) || blank(response.algorithmVersion())
                || response.analyzedAt() == null || response.clusters() == null
                || response.purchaseLimitEvaluations() == null) {
            throw invalid("v2 response is not a completed Louvain analysis");
        }
        Map<ScopeKey, Track1PurchaseLimitAnalysisRequest.PerformanceScope> requested = new HashMap<>();
        request.performanceScopes().forEach(scope -> requested.put(ScopeKey.of(scope), scope));
        Set<ScopeKey> received = new HashSet<>();
        for (Track1PurchaseLimitAnalysisResponse.PurchaseLimitEvaluation item : response.purchaseLimitEvaluations()) {
            if (item == null || item.internalEventId() == null || item.performanceId() == null || blank(item.eventId())
                    || item.status() == null || item.accountLimitFindings() == null) throw invalid("v2 evaluation is incomplete");
            Track1PurchaseLimitAnalysisRequest.PerformanceScope scope = requested.get(ScopeKey.of(item));
            if (scope == null || !received.add(ScopeKey.of(item))) throw invalid("v2 evaluation scope mismatches request");
            evaluation(scope, ticketsFor(request, scope), item);
        }
        if (received.size() != requested.size()) throw invalid("v2 response omitted a requested performance");
    }

    private static List<Track1PurchaseLimitAnalysisRequest.Ticket> ticketsFor(
            Track1PurchaseLimitAnalysisRequest request,
            Track1PurchaseLimitAnalysisRequest.PerformanceScope scope
    ) {
        return request.tickets().stream()
                .filter(ticket -> scope.performanceId().equals(ticket.performanceId())).toList();
    }

    private static void evaluation(Track1PurchaseLimitAnalysisRequest.PerformanceScope scope,
            List<Track1PurchaseLimitAnalysisRequest.Ticket> tickets,
            Track1PurchaseLimitAnalysisResponse.PurchaseLimitEvaluation evaluation) {
        Track1PurchaseLimitAnalysisResponse.EvaluationStatus expected = expectedNotEvaluated(scope, tickets);
        if (evaluation.status() == Track1PurchaseLimitAnalysisResponse.EvaluationStatus.EVALUATED) {
            if (scope.policyVerificationStatus() != Track1PurchaseLimitAnalysisRequest.PolicyVerificationStatus.CONFIRMED
                    || expected != null || !scope.policy().policyVersion().equals(evaluation.policyVersion())) {
                throw invalid("unconfirmed policy was evaluated");
            }
            for (Track1PurchaseLimitAnalysisResponse.AccountLimitFinding finding : evaluation.accountLimitFindings()) {
                if (finding == null || blank(finding.accountId()) || finding.effectiveTicketQuantity() < 1
                        || finding.maxTicketsPerAccount() != scope.policy().maxTicketsPerAccount()
                        || !scope.policy().policyVersion().equals(finding.policyVersion())
                        || finding.reservationQuantities() == null || finding.reservationQuantities().isEmpty()) {
                    throw invalid("v2 limit finding is not bound to the confirmed policy");
                }
            }
            return;
        }
        if (!evaluation.accountLimitFindings().isEmpty()) throw invalid("non-evaluated scope returned limit findings");
        if (expected != null && evaluation.status() != expected) {
            throw invalid("v2 non-evaluation status mismatches sourced policy or quantity data");
        }
        if (evaluation.status() == Track1PurchaseLimitAnalysisResponse.EvaluationStatus.POLICY_NOT_CONFIRMED
                && scope.policyVerificationStatus() != Track1PurchaseLimitAnalysisRequest.PolicyVerificationStatus.POLICY_NOT_CONFIRMED) {
            throw invalid("policy-not-confirmed status mismatches request");
        }
    }

    private static Track1PurchaseLimitAnalysisResponse.EvaluationStatus expectedNotEvaluated(
            Track1PurchaseLimitAnalysisRequest.PerformanceScope scope,
            List<Track1PurchaseLimitAnalysisRequest.Ticket> tickets
    ) {
        if (scope.policyVerificationStatus() == Track1PurchaseLimitAnalysisRequest.PolicyVerificationStatus.POLICY_NOT_CONFIRMED) {
            return Track1PurchaseLimitAnalysisResponse.EvaluationStatus.POLICY_NOT_CONFIRMED;
        }
        if (!completeQuantity(scope, tickets)) {
            return Track1PurchaseLimitAnalysisResponse.EvaluationStatus.QUANTITY_DATA_INCOMPLETE;
        }
        if (tickets.stream().anyMatch(ticket -> !appliesAt(scope.policy(), ticket.purchasedAt()))) {
            return Track1PurchaseLimitAnalysisResponse.EvaluationStatus.POLICY_TIME_NOT_APPLICABLE;
        }
        return null;
    }

    private static boolean completeQuantity(Track1PurchaseLimitAnalysisRequest.PerformanceScope scope,
            List<Track1PurchaseLimitAnalysisRequest.Ticket> tickets) {
        if (scope.quantityDataStatus() != Track1PurchaseLimitAnalysisRequest.QuantityDataStatus.COMPLETE
                || scope.cancellationHandling() != Track1PurchaseLimitAnalysisRequest.CancellationHandling.NETTED
                || scope.duplicateHandling() != Track1PurchaseLimitAnalysisRequest.DuplicateHandling.DEDUPLICATED
                || blank(scope.quantitySourceVersion()) || scope.quantityAsOf() == null
                || tickets.stream().anyMatch(ticket -> ticket.purchasedAt().isAfter(scope.quantityAsOf()))) return false;
        boolean typed = tickets.stream().anyMatch(ticket -> ticket.quantityInputType() != null);
        if (!typed) return tickets.stream().noneMatch(Track1PurchaseLimitContractValidator::hasAnyProvenance);
        if (tickets.stream().anyMatch(ticket -> ticket.quantityInputType()
                != Track1PurchaseLimitAnalysisRequest.QuantityInputType.INDIVIDUAL_TICKET)) return false;
        Map<String, TicketKey> references = new HashMap<>();
        Map<TicketKey, Long> rowsPerReservation = new HashMap<>();
        tickets.forEach(ticket -> rowsPerReservation.merge(TicketKey.reservation(ticket), 1L, Long::sum));
        for (Track1PurchaseLimitAnalysisRequest.Ticket ticket : tickets) {
            if (ticket.ticketStatus() != Track1PurchaseLimitAnalysisRequest.TicketStatus.ACTIVE
                    || !scope.quantitySourceVersion().equals(ticket.sourceVersion()) || ticket.sourceAsOf() == null
                    || ticket.sourceAsOf().isAfter(scope.quantityAsOf()) || ticket.purchasedAt().isAfter(ticket.sourceAsOf())) return false;
            TicketKey key = TicketKey.reservation(ticket);
            if (blank(ticket.ticketReference())) {
                if (rowsPerReservation.get(key) > 1) return false;
            } else if (references.putIfAbsent(ticket.ticketReference(), key) != null
                    && !references.get(ticket.ticketReference()).equals(key)) return false;
        }
        return true;
    }

    private static boolean appliesAt(Track1PurchaseLimitAnalysisRequest.ConfirmedPolicy policy,
            java.time.OffsetDateTime purchasedAt) {
        return !purchasedAt.isBefore(policy.effectiveFrom())
                && (policy.effectiveUntil() == null || purchasedAt.isBefore(policy.effectiveUntil()));
    }

    private static void ticketProvenance(Track1PurchaseLimitAnalysisRequest.Ticket ticket) {
        if (ticket.ticketReference() != null && blank(ticket.ticketReference())) throw invalid("ticket reference is blank");
    }

    private static boolean hasAnyProvenance(Track1PurchaseLimitAnalysisRequest.Ticket ticket) {
        return ticket.quantityInputType() != null || ticket.ticketReference() != null || ticket.ticketStatus() != null
                || ticket.sourceVersion() != null || ticket.sourceAsOf() != null;
    }

    private static void policy(Track1PurchaseLimitAnalysisRequest.ConfirmedPolicy policy) {
        if (policy.maxTicketsPerAccount() < 1 || blank(policy.policyVersion()) || policy.effectiveFrom() == null
                || (policy.effectiveUntil() != null && !policy.effectiveUntil().isAfter(policy.effectiveFrom()))) {
            throw invalid("confirmed policy is invalid");
        }
    }

    private static void tokens(String... values) {
        boolean signal = false;
        for (String value : values) {
            if (value != null) {
                if (!HASH.matcher(value).matches()) throw invalid("v2 ticket hash is invalid");
                signal = true;
            }
        }
        if (!signal) throw invalid("v2 ticket has no relationship signal");
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static FastApiResponseContractException invalid(String message) {
        return new FastApiResponseContractException(message);
    }

    private record ScopeKey(UUID internalEventId, String eventId, UUID performanceId) {
        static ScopeKey of(Track1PurchaseLimitAnalysisRequest.PerformanceScope scope) {
            return new ScopeKey(scope.internalEventId(), scope.eventId(), scope.performanceId());
        }
        static ScopeKey of(Track1PurchaseLimitAnalysisResponse.PurchaseLimitEvaluation scope) {
            return new ScopeKey(scope.internalEventId(), scope.eventId(), scope.performanceId());
        }
    }
    private record TicketKey(String accountId, String reservationNo, String eventId, UUID performanceId,
            String ticketReference) {
        static TicketKey of(Track1PurchaseLimitAnalysisRequest.Ticket ticket) {
            return new TicketKey(ticket.accountId(), ticket.reservationNo(), ticket.eventId(), ticket.performanceId(),
                    ticket.ticketReference());
        }
        static TicketKey reservation(Track1PurchaseLimitAnalysisRequest.Ticket ticket) {
            return new TicketKey(ticket.accountId(), ticket.reservationNo(), ticket.eventId(), ticket.performanceId(), null);
        }
    }
}
