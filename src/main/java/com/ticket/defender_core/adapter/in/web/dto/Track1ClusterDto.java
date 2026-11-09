package com.ticket.defender_core.adapter.in.web.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class Track1ClusterDto {
    private Track1ClusterDto() {
    }

    public record Event(UUID id, String externalEventId, String name) {
    }

    public record Summary(
            String analysisRequestId,
            String clusterId,
            Double riskScore,
            String analysisAlgorithm,
            String analysisAlgorithmVersion,
            OffsetDateTime analyzedAt,
            long storedMemberCount
    ) {
    }

    public enum LedgerSeatStatus { FOUND, NOT_IN_LEDGER, LEDGER_UNAVAILABLE }
    public enum VerificationStatus { NOT_REQUESTED, CURRENT_COMPLETE, LEDGER_UNAVAILABLE }
    public enum VerificationBasis { RESERVATION_REFERENCE }

    /** Describes whether the optional current Track 2 seat-ledger comparison was performed. */
    public record VerificationContext(VerificationStatus status, VerificationBasis basis,
                                      UUID performanceId, String sourceVersion) { }

    /** The raw account and reservation references remain in protected evidence and are never returned. */
    public record Seat(String zone, String row, String seat) { }
    public record LedgerSeat(LedgerSeatStatus status, String zone, String row, String seat, List<Seat> seats) {
        public LedgerSeat(LedgerSeatStatus status, String zone, String row, String seat) {
            this(status, zone, row, seat, zone == null ? List.of() : List.of(new Seat(zone, row, seat)));
        }
    }

    public record Member(Long auditId, LedgerSeat ledgerSeat) {
    }

    /** Shared signal values are deliberately not exposed. Counts refer only to persisted cluster members. */
    public enum SharedSignalType { DEVICE_ID_HASH, IP_HASH }

    public record SharedSignal(SharedSignalType type, long sharedMemberCount, long verifiedConnectionCount) {
    }

    /**
     * A partial explanation of persisted evidence, not a reconstruction of the original graph or a fraud finding.
     * verifiedConnectionCount is the sum of unordered member pairs for every repeated stored signal value.
     */
    public record RelationshipExplanation(
            List<SharedSignal> sharedSignals,
            long verifiedConnectionCount,
            List<String> unavailableSignalTypes,
            String investigationPrioritySource,
            String normalSharingNotice,
            String memberCoverage
    ) {
    }

    public record Page(
            Event event,
            List<Summary> items,
            int page,
            int pageSize,
            long totalItems,
            boolean hasNext,
            String coverage
    ) {
    }

    /** 저장된 device/IP 공유 근거에서만 만든 그래프입니다. 식별자는 군집별 불투명 값이며 신호 원문과 HMAC을 담지 않습니다. */
    public record GraphNode(String id, String kind, String label) { }
    public record GraphEdge(String id, String source, String target, String signalKind) { }
    public record Graph(List<GraphNode> nodes, List<GraphEdge> edges) { }

    public record Detail(Event event, Summary cluster, List<Member> members,
                         RelationshipExplanation relationship, Graph graph, String coverage,
                         VerificationContext verificationContext) {
    }
}
