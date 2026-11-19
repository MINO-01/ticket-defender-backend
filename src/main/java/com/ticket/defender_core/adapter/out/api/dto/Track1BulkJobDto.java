package com.ticket.defender_core.adapter.out.api.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Data #36 bulk-job contract. These records deliberately contain only pseudonymous identifiers. */
public final class Track1BulkJobDto {
    private Track1BulkJobDto() { }
    public record Create(Manifest manifest) { }
    public record Accepted(UUID jobId, String status, boolean existing) { }
    public record Status(UUID jobId, String status, int inputRecordCount, int processedRecordCount,
                         String algorithmVersion, String failureCode, OffsetDateTime createdAt, OffsetDateTime updatedAt) { }
    public record Manifest(String artifactId, String objectKey, String objectVersion, String sha256, long recordCount,
                           String inputFormatVersion, String tenantId, String eventId, String ledgerVersion,
                           String ledgerGeneration, String ledgerRevision, String sourceVersion, OffsetDateTime sourceAsOf,
                           String hmacPurpose, String hmacKeyVersion, String policyVersion, List<Scope> performanceScopes) { }
    public record Scope(UUID internalEventId, String eventId, UUID performanceId, String policyVerificationStatus,
                        ConfirmedPolicy policy, String quantityDataStatus, String quantitySourceVersion,
                        OffsetDateTime quantityAsOf, String cancellationHandling, String duplicateHandling,
                        String dataCompletenessReason) { }
    public record ConfirmedPolicy(int maxTicketsPerAccount, String policyVersion, OffsetDateTime effectiveFrom,
                                  OffsetDateTime effectiveUntil) { }
    public record Results(UUID jobId, String status, String algorithmVersion, String nextCursor, List<Cluster> clusters,
                          String sharedSignalObservationsStatus, List<Object> sharedSignalObservations,
                          String purchaseLimitEvaluationsStatus, List<Object> purchaseLimitEvaluations) { }
    public record Cluster(String clusterId, double riskScore, int memberCount, List<Member> members,
                          boolean hasMoreMembers, List<String> paymentHashes, List<String> addressHashes,
                          List<String> deviceIdHashes, List<String> ipHashes) { }
    public record Members(UUID jobId, String clusterId, String nextCursor, List<Member> members) { }
    public record Member(String accountId, String reservationNo, String eventId) { }
    public record SharedSignalObservationPage(UUID jobId, String status, String nextCursor,
                                              String sharedSignalObservationsStatus,
                                              List<SharedSignalObservation> sharedSignalObservations) { }
    public record SharedSignalObservation(String observationId, String scopeType, String scopeId, String signalType,
                                          int distinctAccountCount, int scopeAccountCount, int distinctReservationCount,
                                          Long totalTicketQuantity, String ticketQuantityStatus,
                                          SharedSignalCorroboration corroboration, String ipNetworkType,
                                          Long purchaseWindowSeconds, boolean excludedFromLouvain) { }
    public record SharedSignalCorroboration(int paymentSharedAccountCount, int deviceSharedAccountCount) { }
    public record PurchaseLimitEvaluationPage(UUID jobId, String status, String nextCursor,
                                              String purchaseLimitEvaluationsStatus,
                                              List<PurchaseLimitEvaluation> purchaseLimitEvaluations) { }
    public record PurchaseLimitEvaluation(UUID internalEventId, String eventId, UUID performanceId, String status,
                                          String policyVersion, int accountLimitFindingCount) { }
    public record PurchaseLimitFindingPage(UUID jobId, UUID performanceId, String status, String nextCursor,
                                           String evaluationStatus, String policyVersion,
                                           List<AccountLimitFinding> accountLimitFindings) { }
    public record AccountLimitFinding(String accountId, long effectiveTicketQuantity, int maxTicketsPerAccount,
                                      String policyVersion, List<ReservationQuantity> reservationQuantities) { }
    public record ReservationQuantity(String reservationNo, long effectiveTicketQuantity) { }
}
