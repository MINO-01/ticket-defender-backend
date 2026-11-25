package com.ticket.defender_core.adapter.out.api.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 별도 다공연 Data 계약. 이 DTO는 내부 인증된 Spring 호출에만 사용한다. */
public final class MultiEventAnalysisDto {
    private MultiEventAnalysisDto() { }

    public record Request(UUID analysisId, UUID tenantId, String ledgerVersionSetDigest, OffsetDateTime analysisAsOf,
                          OffsetDateTime analysisStartAt, OffsetDateTime analysisEndAt,
                          String scoreVersion, String projectionVersion, List<LedgerScope> ledgerScopes,
                          List<Ticket> tickets) { }
    public record LedgerScope(String eventId, UUID performanceId, UUID ledgerVersionId, String sourceBatchId,
                              UUID policySnapshotId) {
        public LedgerScope(String eventId, UUID performanceId, UUID ledgerVersionId, String sourceBatchId) {
            this(eventId, performanceId, ledgerVersionId, sourceBatchId, null);
        }
    }
    public record Ticket(String accountId, String reservationNo, String eventId, UUID performanceId, UUID ledgerVersionId,
                         Long sourceRowOrdinal, String supplierNamespace, String hmacPurpose, String hmacKeyVersion, OffsetDateTime purchasedAt,
                         Integer effectiveTicketQuantity, Integer confirmedPurchaseLimit, UUID quantityPolicySnapshotId,
                         String quantityPolicyVersion, String quantitySourceVersion, String paymentHash, String addressHash, String deviceIdHash,
                         String ipHash, String refundAccountHash, String phoneHash, String ipNetworkType,
                         String quantityStatus, String quantityReason, String sourceVersion) { }
    public record Response(UUID analysisId, String ledgerVersionSetDigest, String scoreVersion,
                           String projectionScoreVersion, String projectionVersion,
                           String algorithm, String algorithmVersion, List<Candidate> candidates) { }
    public record Candidate(String candidateId, String priorityBand, String scoreVersion, String projectionVersion,
                            int distinctAccountCount, int distinctEventCount, int distinctPerformanceCount,
                            int verifiedRelationshipCount, List<LedgerScope> sourceScopes, List<String> availableSignals,
                            List<String> excludedSignals, List<String> unavailableSignals,
                            List<String> normalSharingPossibilities, ScoreBreakdown scoreBreakdown,
                            List<String> memberAccountIds, List<Relationship> relationships) { }
    public record Relationship(String signalType, List<String> accountIds, List<LedgerScope> sourceScopes) { }
    public record ScoreBreakdown(double L, double G, double B, double Q, double R, double score,
                                 String quantityStatus, String quantityReason) { }
}
