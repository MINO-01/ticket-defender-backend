package com.ticket.defender_core.adapter.out.api;

import com.ticket.defender_core.adapter.out.api.dto.Track1BulkJobDto;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Rejects malformed opt-in Data pages; a contract failure is never a valid empty result. */
final class Track1BulkAuxiliaryContractValidator {
    private static final Set<String> EVALUATION_STATUSES = Set.of("EVALUATED", "POLICY_NOT_CONFIRMED",
            "QUANTITY_DATA_INCOMPLETE", "POLICY_TIME_NOT_APPLICABLE");
    private static final Set<String> NETWORK_TYPES = Set.of("MOBILE_CGNAT", "RESIDENTIAL", "CORPORATE_EDU", "DATACENTER_PROXY", "UNKNOWN");
    private Track1BulkAuxiliaryContractValidator() { }

    static void shared(UUID expectedJob, Track1BulkJobDto.SharedSignalObservationPage page) {
        if (page == null || !expectedJob.equals(page.jobId()) || !"COMPLETED".equals(page.status())
                || !"COMPLETED".equals(page.sharedSignalObservationsStatus()) || page.sharedSignalObservations() == null)
            invalid();
        Set<String> ids = new HashSet<>();
        for (var item : page.sharedSignalObservations()) {
            if (item == null || blank(item.observationId()) || !ids.add(item.observationId())
                    || !"PERFORMANCE".equals(item.scopeType()) || blank(item.scopeId())
                    || !("IP".equals(item.signalType()) || "ADDRESS".equals(item.signalType()))
                    || item.distinctAccountCount() < 2 || item.scopeAccountCount() < 2 || item.distinctReservationCount() < 1
                    || item.totalTicketQuantity() != null && item.totalTicketQuantity() < 1
                    || !("VERIFIED".equals(item.ticketQuantityStatus()) || "UNAVAILABLE".equals(item.ticketQuantityStatus()))
                    || item.corroboration() == null || item.corroboration().paymentSharedAccountCount() < 0
                    || item.corroboration().deviceSharedAccountCount() < 0 || !NETWORK_TYPES.contains(item.ipNetworkType())
                    || item.purchaseWindowSeconds() != null && item.purchaseWindowSeconds() < 0) invalid();
            if (("VERIFIED".equals(item.ticketQuantityStatus())) != (item.totalTicketQuantity() != null)) invalid();
        }
        cursor(page.nextCursor());
    }

    static void evaluations(UUID expectedJob, Track1BulkJobDto.PurchaseLimitEvaluationPage page) {
        if (page == null || !expectedJob.equals(page.jobId()) || !"COMPLETED".equals(page.status())
                || !"COMPLETED".equals(page.purchaseLimitEvaluationsStatus()) || page.purchaseLimitEvaluations() == null) invalid();
        Set<UUID> performances = new HashSet<>();
        for (var item : page.purchaseLimitEvaluations()) {
            if (item == null || item.internalEventId() == null || item.performanceId() == null || blank(item.eventId())
                    || !performances.add(item.performanceId()) || !EVALUATION_STATUSES.contains(item.status())
                    || item.accountLimitFindingCount() < 0) invalid();
            if (("EVALUATED".equals(item.status())) != (item.policyVersion() != null && !item.policyVersion().isBlank())) invalid();
            if (!"EVALUATED".equals(item.status()) && item.accountLimitFindingCount() != 0) invalid();
        }
        cursor(page.nextCursor());
    }

    static void findings(UUID expectedJob, UUID expectedPerformance, Track1BulkJobDto.PurchaseLimitFindingPage page) {
        if (page == null || !expectedJob.equals(page.jobId()) || !expectedPerformance.equals(page.performanceId())
                || !"COMPLETED".equals(page.status()) || !"EVALUATED".equals(page.evaluationStatus())
                || blank(page.policyVersion()) || page.accountLimitFindings() == null) invalid();
        Set<String> accounts = new HashSet<>();
        for (var item : page.accountLimitFindings()) {
            if (item == null || !token(item.accountId()) || !accounts.add(item.accountId()) || item.effectiveTicketQuantity() < 1
                    || item.maxTicketsPerAccount() < 1 || !page.policyVersion().equals(item.policyVersion())
                    || item.reservationQuantities() == null || item.reservationQuantities().isEmpty()) invalid();
            Set<String> reservations = new HashSet<>();
            for (var reservation : item.reservationQuantities())
                if (reservation == null || !token(reservation.reservationNo()) || !reservations.add(reservation.reservationNo())
                        || reservation.effectiveTicketQuantity() < 1) invalid();
        }
        cursor(page.nextCursor());
    }
    private static boolean token(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void cursor(String cursor) { if (cursor != null && !cursor.matches("\\d+")) invalid(); }
    private static void invalid() { throw new FastApiResponseContractException("Data auxiliary response violates the contract", null); }
}
