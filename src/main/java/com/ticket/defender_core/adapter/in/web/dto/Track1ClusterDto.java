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

    public record Member(Long auditId, String accountId, String reservationNo) {
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

    public record Detail(Event event, Summary cluster, List<Member> members,
                         RelationshipExplanation relationship, String coverage) {
    }

    /**
     * A display-safe reconstruction from MySQL evidence.  Node ids and labels are ordinal aliases,
     * never account ids, reservation numbers, or HMAC values.
     */
    public record Graph(
            String analysisRequestId,
            String clusterId,
            String algorithmVersion,
            String coverage,
            List<GraphNode> nodes,
            List<GraphEdge> edges,
            List<String> warnings
    ) {
    }

    public enum GraphNodeKind { ACCOUNT, RESERVATION, SIGNAL }

    public enum GraphEdgeKind { RESERVATION, SHARED_SIGNAL }

    public record GraphNode(String id, String label, GraphNodeKind kind) {
    }

    public record GraphEdge(String id, String source, String target, GraphEdgeKind kind) {
    }
}
