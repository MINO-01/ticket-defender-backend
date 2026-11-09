package com.ticket.defender_core.adapter.in.web.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Track 1 구매 수량 원장과 v2 분석 실행의 관리자 API 계약입니다. 응답에는 원문 참조와 저장 HMAC을 넣지 않습니다. */
public final class Track1QuantityDto {
    private Track1QuantityDto() { }

    public record ImportRequest(UUID performanceId, String inputType, String sourceVersion, OffsetDateTime sourceAsOf,
                                String quantityBasis, Boolean completeSnapshot, List<ImportRow> rows) { }

    public record ImportRow(String accountReference, String reservationReference, String ticketReference,
                            OffsetDateTime purchasedAt, Integer quantity, String quantityUnit, String ticketStatus,
                            String paymentReference, String addressReference, String deviceReference, String ipReference) { }

    public record ImportError(Integer rowIndex, String code) { }

    public record LedgerBatch(UUID batchId, UUID eventId, UUID performanceId, String inputType, String sourceVersion,
                              OffsetDateTime sourceAsOf, int ledgerVersion, String quantityBasis, boolean completeSnapshot,
                              String status, int inputRowCount, int acceptedRowCount, int rejectedRowCount,
                              List<ImportError> errors, OffsetDateTime completedAt) { }

    public record RunRequest(UUID performanceId, UUID ledgerBatchId) { }

    public record ReservationQuantity(String reservationToken, int effectiveTicketQuantity) { }

    public record Finding(String accountToken, int effectiveTicketQuantity, int maxTicketsPerAccount, String policyVersion,
                          List<ReservationQuantity> reservationQuantities) { }

    public record Evaluation(String status, String reasonCode, String policyVersion, List<Finding> findings) { }

    public record Cluster(String clusterId, double riskScore, int memberCount) { }

    public record AnalysisRun(UUID runId, UUID eventId, UUID performanceId, UUID ledgerBatchId, String sourceVersion,
                              String executionStatus, String failureCode, boolean retryable, UUID policyId,
                              String policyVersion, String algorithmVersion, UUID analysisRequestId, Integer clusterCount,
                              List<Cluster> clusters, int nettedExcludedRowCount, OffsetDateTime requestedAt,
                              OffsetDateTime completedAt, Evaluation evaluation) { }

    public record Page<T>(List<T> items, int page, int pageSize, boolean hasNext) { }
}
