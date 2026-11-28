package com.ticket.defender_core.adapter.out.api.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonInclude;

/** 별도 다공연 Data 계약. 이 DTO는 내부 인증된 Spring 호출에만 사용한다. */
public final class MultiEventAnalysisDto {
    private MultiEventAnalysisDto() { }

    public record Request(UUID analysisId, UUID tenantId, String ledgerVersionSetDigest, OffsetDateTime analysisAsOf,
                          OffsetDateTime analysisStartAt, OffsetDateTime analysisEndAt,
                          String scoreVersion, String projectionVersion, List<LedgerScope> ledgerScopes,
                          List<Ticket> tickets,
                          @JsonInclude(JsonInclude.Include.NON_EMPTY) List<CancelledTicket> cancelledTickets) {
        public Request {
            ledgerScopes = ledgerScopes == null ? null : List.copyOf(ledgerScopes);
            tickets = tickets == null ? null : List.copyOf(tickets);
            cancelledTickets = cancelledTickets == null ? List.of() : List.copyOf(cancelledTickets);
        }

        public Request(UUID analysisId, UUID tenantId, String ledgerVersionSetDigest, OffsetDateTime analysisAsOf,
                       OffsetDateTime analysisStartAt, OffsetDateTime analysisEndAt,
                       String scoreVersion, String projectionVersion, List<LedgerScope> ledgerScopes,
                       List<Ticket> tickets) {
            this(analysisId, tenantId, ledgerVersionSetDigest, analysisAsOf, analysisStartAt, analysisEndAt,
                    scoreVersion, projectionVersion, ledgerScopes, tickets, List.of());
        }
    }
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
                         String quantityStatus, String quantityReason, String sourceVersion,
                         @JsonInclude(JsonInclude.Include.NON_NULL) String seatZone,
                         @JsonInclude(JsonInclude.Include.NON_NULL) String seatRow,
                         @JsonInclude(JsonInclude.Include.NON_NULL) String seatNumber,
                         @JsonInclude(JsonInclude.Include.NON_NULL) OffsetDateTime reservedAt,
                         @JsonInclude(JsonInclude.Include.NON_NULL) OffsetDateTime paidAt,
                         @JsonInclude(JsonInclude.Include.NON_NULL) OffsetDateTime accountCreatedAt) {
        public Ticket(String accountId, String reservationNo, String eventId, UUID performanceId, UUID ledgerVersionId,
                      Long sourceRowOrdinal, String supplierNamespace, String hmacPurpose, String hmacKeyVersion,
                      OffsetDateTime purchasedAt, Integer effectiveTicketQuantity, Integer confirmedPurchaseLimit,
                      UUID quantityPolicySnapshotId, String quantityPolicyVersion, String quantitySourceVersion,
                      String paymentHash, String addressHash, String deviceIdHash, String ipHash,
                      String refundAccountHash, String phoneHash, String ipNetworkType, String quantityStatus,
                      String quantityReason, String sourceVersion) {
            this(accountId, reservationNo, eventId, performanceId, ledgerVersionId, sourceRowOrdinal,
                    supplierNamespace, hmacPurpose, hmacKeyVersion, purchasedAt, effectiveTicketQuantity,
                    confirmedPurchaseLimit, quantityPolicySnapshotId, quantityPolicyVersion, quantitySourceVersion,
                    paymentHash, addressHash, deviceIdHash, ipHash, refundAccountHash, phoneHash, ipNetworkType,
                    quantityStatus, quantityReason, sourceVersion, null, null, null, null, null, null);
        }
    }
    /** Separate pseudonymous cancellation evidence; never participates in the active ticket analysis list. */
    public record CancelledTicket(String accountHmac, String ticketHmac, String eventId, UUID performanceId,
                                  UUID ledgerVersionId, String supplierNamespace, String hmacPurpose,
                                  String hmacKeyVersion, String sourceVersion, long sourceRowOrdinal,
                                  String seatZone, String seatRow, String seatNumber, OffsetDateTime cancelledAt) { }
    public record Response(UUID analysisId, String ledgerVersionSetDigest, String scoreVersion,
                           String projectionScoreVersion, String projectionVersion,
                           String algorithm, String algorithmVersion, List<Candidate> candidates) { }
    /** Immutable artifact registration used by the large, asynchronous path. */
    public record ArtifactManifest(String artifactId, String objectKey, String objectVersion, String sha256,
                                   long recordCount,
                                   @JsonInclude(JsonInclude.Include.NON_DEFAULT) int cancelledTicketRecordCount,
                                   String inputFormatVersion,
                                   UUID analysisId, UUID tenantId, String supplierNamespace, String hmacPurpose,
                                   String hmacKeyVersion, String ledgerVersionSetDigest, OffsetDateTime analysisAsOf,
                                   OffsetDateTime analysisStartAt, OffsetDateTime analysisEndAt,
                                   String scoreVersion, String projectionVersion, List<ArtifactLedgerScope> ledgerScopes,
                                   Exp3QuantityEvidenceArtifact exp3QuantityEvidenceArtifact) {
        public ArtifactManifest(String artifactId, String objectKey, String objectVersion, String sha256,
                               long recordCount, int cancelledTicketRecordCount, String inputFormatVersion,
                               UUID analysisId, UUID tenantId, String supplierNamespace, String hmacPurpose,
                               String hmacKeyVersion, String ledgerVersionSetDigest, OffsetDateTime analysisAsOf,
                               OffsetDateTime analysisStartAt, OffsetDateTime analysisEndAt, String scoreVersion,
                               String projectionVersion, List<ArtifactLedgerScope> ledgerScopes) {
            this(artifactId, objectKey, objectVersion, sha256, recordCount, cancelledTicketRecordCount,
                    inputFormatVersion, analysisId, tenantId, supplierNamespace, hmacPurpose, hmacKeyVersion,
                    ledgerVersionSetDigest, analysisAsOf, analysisStartAt, analysisEndAt, scoreVersion,
                    projectionVersion, ledgerScopes, null);
        }
        public ArtifactManifest(String artifactId, String objectKey, String objectVersion, String sha256,
                               long recordCount, String inputFormatVersion, UUID analysisId, UUID tenantId,
                               String supplierNamespace, String hmacPurpose, String hmacKeyVersion,
                               String ledgerVersionSetDigest, OffsetDateTime analysisAsOf,
                               OffsetDateTime analysisStartAt, OffsetDateTime analysisEndAt,
                               String scoreVersion, String projectionVersion, List<ArtifactLedgerScope> ledgerScopes) {
            this(artifactId, objectKey, objectVersion, sha256, recordCount, 0, inputFormatVersion, analysisId, tenantId,
                    supplierNamespace, hmacPurpose, hmacKeyVersion, ledgerVersionSetDigest, analysisAsOf,
                    analysisStartAt, analysisEndAt, scoreVersion, projectionVersion, ledgerScopes, null);
        }
    }
    public record ArtifactCancelledTicketRecord(String recordType, CancelledTicket cancelledTicket) { }
    public record Exp3QuantityEvidenceArtifact(String contractVersion, String artifactId, String objectKey,
            String objectVersion, String sha256, long recordCount) { }
    /** Separate exp3-only quantity scope totals. This sidecar does not alter exp2 scoring. */
    public record Exp3QuantityScopeManifestRecord(String recordType, String contractVersion, UUID analysisId,
            UUID tenantId, String supplierNamespace, String eventId, UUID performanceId, UUID ledgerVersionId,
            String hmacPurpose, String hmacKeyVersion, String sourceVersion, OffsetDateTime sourceAsOf,
            OffsetDateTime analysisStartAt, OffsetDateTime analysisEndAt, boolean ledgerCompleteSnapshot,
            String quantityDataStatus, String cancellationHandling, String duplicateHandling,
            String duplicateSelection, boolean duplicateSourceRowsRejected, String policyVerificationStatus,
            UUID policySnapshotId, String policyVersion, String policySourceVersion, Integer confirmedPurchaseLimit,
            int expectedAccountCount, int expectedActiveSourceRowCount, int expectedTicketUnitRowCount,
            int expectedOrderLineRowCount, long expectedOrderLineTicketQuantity, long expectedActualTicketQuantity,
            String rebookingEvidenceStatus, int expectedReservationEventCount, int expectedCancellationEventCount) { }
    /** Actual active quantity for one account HMAC and one fixed performance scope. */
    public record Exp3AccountQuantityEvidenceRecord(String recordType, String contractVersion, UUID analysisId,
            UUID tenantId, String supplierNamespace, String eventId, UUID performanceId, UUID ledgerVersionId,
            String hmacPurpose, String hmacKeyVersion, String accountHmac, String sourceVersion,
            OffsetDateTime sourceAsOf, OffsetDateTime analysisStartAt, OffsetDateTime analysisEndAt,
            int activeSourceRowCount, int ticketUnitRowCount, int orderLineRowCount,
            long orderLineTicketQuantity, long actualTicketQuantity) { }
    public record Exp3RebookingEventRecord(String recordType, String contractVersion, UUID analysisId,
            UUID tenantId, String supplierNamespace, String eventId, UUID performanceId, UUID ledgerVersionId,
            String hmacPurpose, String hmacKeyVersion, String accountHmac, String ticketHmac,
            String sourceVersion, OffsetDateTime sourceAsOf, OffsetDateTime analysisStartAt,
            OffsetDateTime analysisEndAt, String eventType, OffsetDateTime eventAt,
            String seatZone, String seatRow, String seatNumber) { }
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
