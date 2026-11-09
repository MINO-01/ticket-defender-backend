package com.ticket.defender_core.adapter.out.api;

import com.ticket.defender_core.adapter.out.api.dto.Track1PurchaseLimitAnalysisRequest;
import com.ticket.defender_core.adapter.out.api.dto.Track1PurchaseLimitAnalysisResponse;

import java.util.HashMap;
import java.util.HashSet;
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
            evaluation(scope, item);
        }
        if (received.size() != requested.size()) throw invalid("v2 response omitted a requested performance");
    }

    private static void evaluation(Track1PurchaseLimitAnalysisRequest.PerformanceScope scope,
            Track1PurchaseLimitAnalysisResponse.PurchaseLimitEvaluation evaluation) {
        if (evaluation.status() == Track1PurchaseLimitAnalysisResponse.EvaluationStatus.EVALUATED) {
            if (scope.policyVerificationStatus() != Track1PurchaseLimitAnalysisRequest.PolicyVerificationStatus.CONFIRMED
                    || !scope.policy().policyVersion().equals(evaluation.policyVersion())) {
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
        if (evaluation.status() == Track1PurchaseLimitAnalysisResponse.EvaluationStatus.POLICY_NOT_CONFIRMED
                && scope.policyVerificationStatus() != Track1PurchaseLimitAnalysisRequest.PolicyVerificationStatus.POLICY_NOT_CONFIRMED) {
            throw invalid("policy-not-confirmed status mismatches request");
        }
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
    private record TicketKey(String accountId, String reservationNo, String eventId, UUID performanceId) {
        static TicketKey of(Track1PurchaseLimitAnalysisRequest.Ticket ticket) {
            return new TicketKey(ticket.accountId(), ticket.reservationNo(), ticket.eventId(), ticket.performanceId());
        }
    }
}
