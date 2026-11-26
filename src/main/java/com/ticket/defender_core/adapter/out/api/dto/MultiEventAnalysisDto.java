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
    /** Immutable artifact registration used by the large, asynchronous path. */
    public record ArtifactManifest(String artifactId, String objectKey, String objectVersion, String sha256,
                                   long recordCount, String inputFormatVersion, UUID analysisId, UUID tenantId,
                                   String supplierNamespace, String hmacPurpose, String hmacKeyVersion,
                                   String ledgerVersionSetDigest, OffsetDateTime analysisAsOf,
                                   OffsetDateTime analysisStartAt, OffsetDateTime analysisEndAt,
                                   String scoreVersion, String projectionVersion, List<ArtifactLedgerScope> ledgerScopes) { }
    /** Internal source event ID is separate from the supplier-facing event reference used for graph scope. */
    public record ArtifactLedgerScope(String eventId, UUID sourceEventId, UUID performanceId, UUID ledgerVersionId,
                                      String sourceBatchId, UUID policySnapshotId) { }
    public record ArtifactCreate(ArtifactManifest manifest) { }
    public record ArtifactAccepted(UUID jobId, String status, boolean existing) { }
    public record ArtifactStatus(UUID jobId, String status, String ledgerVersionSetDigest,
                                 int inputRecordCount, int processedRecordCount, Integer candidateCount,
                                 String failureCode, String algorithmVersion) { }
    public record CandidateSummary(String candidateId, String priorityBand, String scoreVersion,
                                  String projectionVersion, int distinctAccountCount, int distinctEventCount,
                                  int distinctPerformanceCount, int verifiedRelationshipCount,
                                  List<LedgerScope> sourceScopes, List<String> availableSignals,
                                  List<String> excludedSignals, List<String> unavailableSignals,
                                  List<String> normalSharingPossibilities, ScoreBreakdown scoreBreakdown,
                                  String familyId, String parentCandidateId, int childCount, Double familyScore,
                                  List<EvidenceReason> reasons) { }
    public record CandidatePage(UUID jobId, String ledgerVersionSetDigest, String cursor, String nextCursor,
                                int totalCount, List<CandidateSummary> records) { }
    public record MemberPage(UUID jobId, String candidateId, String ledgerVersionSetDigest, String cursor,
                             String nextCursor, int totalCount, List<String> records) { }
    public record MemberEvidenceReason(String code, int peerCount, List<String> performances) { }
    public record MemberEvidence(String accountReferenceHmac, List<MemberEvidenceReason> reasons) { }
    public record MemberEvidencePage(UUID jobId, String candidateId, String ledgerVersionSetDigest, String cursor,
                                     String nextCursor, int totalCount, List<MemberEvidence> records) { }
    public record EvidenceReason(String code, int count, List<String> performances, int strengthRank) { }
    public record RelationshipSummary(String relationshipId, String signalType, int accountCount,
                                     int sourceScopeCount, List<LedgerScope> sourceScopes) { }
    public record RelationshipPage(UUID jobId, String candidateId, String ledgerVersionSetDigest, String cursor,
                                   String nextCursor, int totalCount, List<RelationshipSummary> records) { }
    public record Candidate(String candidateId, String priorityBand, String scoreVersion, String projectionVersion,
                            int distinctAccountCount, int distinctEventCount, int distinctPerformanceCount,
                            int verifiedRelationshipCount, List<LedgerScope> sourceScopes, List<String> availableSignals,
                            List<String> excludedSignals, List<String> unavailableSignals,
                            List<String> normalSharingPossibilities, ScoreBreakdown scoreBreakdown,
                            List<String> memberAccountIds, List<Relationship> relationships, String familyId,
                            String parentCandidateId, int childCount, Double familyScore,
                            List<EvidenceReason> reasons, List<MemberEvidence> memberReasons) { }
    public record Relationship(String signalType, List<String> accountIds, List<LedgerScope> sourceScopes) { }
    public record ScoreBreakdown(double L, double G, double B, double Q, double R, double score,
                                 String quantityStatus, String quantityReason) { }
}
