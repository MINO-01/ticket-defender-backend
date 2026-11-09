package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.Track1ClusterDto;
import com.ticket.defender_core.adapter.out.persistence.MacroAuditEvidenceReadRepository;
import com.ticket.defender_core.adapter.out.persistence.StoredClusterAggregate;
import com.ticket.defender_core.adapter.out.persistence.TenantEventRepository;
import com.ticket.defender_core.domain.AccessRecordStatus;
import com.ticket.defender_core.domain.MacroAuditEvidence;
import com.ticket.defender_core.domain.TenantEvent;
import com.ticket.defender_core.security.AdminAccessService;
import com.ticket.defender_core.security.AdminActor;
import com.ticket.defender_core.security.AdminRole;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminTrack1ClusterService {
    private static final Set<AdminRole> VIEW_ROLES = Set.of(
            AdminRole.TENANT_ADMIN, AdminRole.ANALYST, AdminRole.REVIEWER, AdminRole.APPROVER);
    private static final String COVERAGE = "STORED_EVIDENCE_ONLY";
    private static final String MEMBER_COVERAGE = "STORED_CLUSTER_MEMBERS_ONLY";
    private static final String GRAPH_COVERAGE = "STORED_EVIDENCE_RECONSTRUCTION";
    private static final int MAX_GRAPH_MEMBER_ROWS = 200;
    private static final String GRAPH_SOURCE_WARNING = "This graph is reconstructed from stored MySQL evidence and is not the original GDS projection.";
    private static final String GRAPH_SIGNAL_WARNING = "Only stored device and IP matches are shown; payment/address signals and request-wide GDS hub exclusions are unavailable.";
    private static final String PRIORITY_SOURCE = "STORED_LOUVAIN_RISK_SCORE";
    private static final String NORMAL_SHARING_NOTICE = "공동 이용 기기 또는 네트워크의 정상 공유 가능성을 함께 검토해야 하며, "
            + "이 관계 정보만으로 동일인·조직 또는 부정행위를 확정할 수 없습니다.";

    private final AdminAccessService access;
    private final TenantEventRepository events;
    private final MacroAuditEvidenceReadRepository evidence;

    @Transactional(readOnly = true)
    public Track1ClusterDto.Page list(UUID eventId, int page, int pageSize, String sort) {
        validatePage(page, pageSize, sort);
        AdminActor actor = access.requireEventAccess(eventId, VIEW_ROLES);
        Track1ClusterDto.Event event = event(actor);
        long totalItems = evidence.countClusters(actor.tenantId(), eventId);
        List<Track1ClusterDto.Summary> items = evidence.findClusters(actor.tenantId(), eventId, sort, page, pageSize)
                .stream().map(AdminTrack1ClusterService::summary).toList();
        return new Track1ClusterDto.Page(event, items, page, pageSize, totalItems,
                ((long) page + 1) * pageSize < totalItems, COVERAGE);
    }

    @Transactional(readOnly = true)
    public Track1ClusterDto.Detail detail(UUID eventId, String analysisRequestId, String clusterId) {
        AdminActor actor = access.requireEventAccess(eventId, VIEW_ROLES);
        Track1ClusterDto.Event event = event(actor);
        List<MacroAuditEvidence> rows = evidence.findMembers(actor.tenantId(), eventId, analysisRequestId, clusterId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found");
        }
        MacroAuditEvidence first = rows.getFirst();
        if (rows.stream().anyMatch(row -> !Objects.equals(first.getRiskScore(), row.getRiskScore())
                || !Objects.equals(first.getAnalysisAlgorithm(), row.getAnalysisAlgorithm())
                || !Objects.equals(first.getAnalysisAlgorithmVersion(), row.getAnalysisAlgorithmVersion())
                || !Objects.equals(first.getAnalysisCompletedAt(), row.getAnalysisCompletedAt()))) {
            throw new IllegalStateException("군집 증거의 분석 출처가 일치하지 않습니다.");
        }
        Track1ClusterDto.Summary cluster = new Track1ClusterDto.Summary(analysisRequestId, clusterId,
                first.getRiskScore(), first.getAnalysisAlgorithm(), first.getAnalysisAlgorithmVersion(),
                first.getAnalysisCompletedAt().atOffset(ZoneOffset.UTC), rows.size());
        List<Track1ClusterDto.Member> members = rows.stream()
                .map(row -> new Track1ClusterDto.Member(row.getAuditId(), row.getAccountId(), row.getReservationNo()))
                .toList();
        return new Track1ClusterDto.Detail(event, cluster, members, relationship(rows), COVERAGE);
    }

    @Transactional(readOnly = true)
    public Track1ClusterDto.Graph graph(UUID eventId, String analysisRequestId, String clusterId) {
        AdminActor actor = access.requireEventAccess(eventId, VIEW_ROLES);
        event(actor);
        List<MacroAuditEvidence> rows = evidence.findMembersForGraph(actor.tenantId(), eventId,
                analysisRequestId, clusterId, MAX_GRAPH_MEMBER_ROWS + 1);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found");
        }
        if (rows.size() > MAX_GRAPH_MEMBER_ROWS) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Graph exceeds the view limit");
        }
        validateClusterProvenance(rows);
        return reconstructGraph(analysisRequestId, clusterId, rows);
    }

    private Track1ClusterDto.Event event(AdminActor actor) {
        TenantEvent stored = events.findById(actor.eventId())
                .filter(item -> actor.tenantId().equals(item.getTenantId())
                        && AccessRecordStatus.ACTIVE.name().equals(item.getStatus()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found"));
        return new Track1ClusterDto.Event(stored.getId(), stored.getExternalEventRef(), stored.getName());
    }

    private static Track1ClusterDto.Summary summary(StoredClusterAggregate row) {
        if (!Objects.equals(row.minimumRiskScore(), row.maximumRiskScore())
                || !Objects.equals(row.firstAlgorithm(), row.lastAlgorithm())
                || !Objects.equals(row.firstAlgorithmVersion(), row.lastAlgorithmVersion())
                || !Objects.equals(row.firstAnalyzedAt(), row.lastAnalyzedAt())) {
            throw new IllegalStateException("군집 증거의 분석 출처가 일치하지 않습니다.");
        }
        return new Track1ClusterDto.Summary(row.analysisRequestId(), row.clusterId(), row.maximumRiskScore(),
                row.lastAlgorithm(), row.lastAlgorithmVersion(),
                row.lastAnalyzedAt().atOffset(ZoneOffset.UTC), row.storedMemberCount());
    }

    private static void validateClusterProvenance(List<MacroAuditEvidence> rows) {
        MacroAuditEvidence first = rows.getFirst();
        if (rows.stream().anyMatch(row -> !Objects.equals(first.getRiskScore(), row.getRiskScore())
                || !Objects.equals(first.getAnalysisAlgorithm(), row.getAnalysisAlgorithm())
                || !Objects.equals(first.getAnalysisAlgorithmVersion(), row.getAnalysisAlgorithmVersion())
                || !Objects.equals(first.getAnalysisCompletedAt(), row.getAnalysisCompletedAt()))) {
            throw new IllegalStateException("Stored cluster analysis provenance is inconsistent");
        }
    }

    private static Track1ClusterDto.Graph reconstructGraph(String analysisRequestId, String clusterId,
            List<MacroAuditEvidence> rows) {
        List<MacroAuditEvidence> orderedRows = rows.stream()
                .sorted(Comparator.comparing(MacroAuditEvidence::getAccountId)
                        .thenComparing(MacroAuditEvidence::getReservationNo)
                        .thenComparing(MacroAuditEvidence::getAuditId))
                .toList();
        Map<String, String> accountNodes = ordinalNodes(orderedRows.stream()
                .map(MacroAuditEvidence::getAccountId).distinct().toList(), "account");
        List<Track1ClusterDto.GraphNode> nodes = new ArrayList<>();
        accountNodes.forEach((account, id) -> nodes.add(new Track1ClusterDto.GraphNode(
                id, "계정 " + ordinal(id), Track1ClusterDto.GraphNodeKind.ACCOUNT)));
        List<Track1ClusterDto.GraphEdge> edges = new ArrayList<>();

        int reservationOrdinal = 0;
        for (MacroAuditEvidence row : orderedRows) {
            String reservationId = "reservation-" + (++reservationOrdinal);
            nodes.add(new Track1ClusterDto.GraphNode(reservationId, "예매 " + reservationOrdinal,
                    Track1ClusterDto.GraphNodeKind.RESERVATION));
            edges.add(new Track1ClusterDto.GraphEdge("edge-reservation-" + reservationOrdinal,
                    accountNodes.get(row.getAccountId()), reservationId, Track1ClusterDto.GraphEdgeKind.RESERVATION));
        }

        List<StoredSignal> signals = repeatedSignalsAcrossAccounts(orderedRows);
        int signalOrdinal = 0;
        for (StoredSignal signal : signals) {
            String signalId = "signal-" + (++signalOrdinal);
            nodes.add(new Track1ClusterDto.GraphNode(signalId,
                    (signal.type() == StoredSignalType.DEVICE ? "공유 기기 " : "공유 IP ") + signalOrdinal,
                    Track1ClusterDto.GraphNodeKind.SIGNAL));
            for (String accountId : signal.accountIds()) {
                edges.add(new Track1ClusterDto.GraphEdge("edge-signal-" + signalOrdinal + "-" + ordinal(accountNodes.get(accountId)),
                        accountNodes.get(accountId), signalId, Track1ClusterDto.GraphEdgeKind.SHARED_SIGNAL));
            }
        }
        List<String> warnings = new ArrayList<>(List.of(GRAPH_SOURCE_WARNING, GRAPH_SIGNAL_WARNING));
        if (signals.isEmpty()) {
            warnings.add("No repeated stored device or IP signal exists across distinct stored accounts.");
        }
        return new Track1ClusterDto.Graph(analysisRequestId, clusterId,
                orderedRows.getFirst().getAnalysisAlgorithmVersion(), GRAPH_COVERAGE,
                List.copyOf(nodes), List.copyOf(edges), List.copyOf(warnings));
    }

    private static Map<String, String> ordinalNodes(List<String> values, String prefix) {
        Map<String, String> nodes = new LinkedHashMap<>();
        int ordinal = 0;
        for (String value : values.stream().sorted().toList()) {
            nodes.put(value, prefix + "-" + (++ordinal));
        }
        return nodes;
    }

    private static String ordinal(String id) {
        return id.substring(id.lastIndexOf('-') + 1);
    }

    private static List<StoredSignal> repeatedSignalsAcrossAccounts(List<MacroAuditEvidence> rows) {
        Map<SignalKey, Set<String>> owners = new java.util.TreeMap<>();
        for (MacroAuditEvidence row : rows) {
            addSignalOwner(owners, StoredSignalType.DEVICE, row.getDeviceIdHash(), row.getAccountId());
            addSignalOwner(owners, StoredSignalType.IP, row.getIpHash(), row.getAccountId());
        }
        return owners.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(entry -> new StoredSignal(entry.getKey().type(), entry.getValue().stream().sorted().toList()))
                .toList();
    }

    private static void addSignalOwner(Map<SignalKey, Set<String>> owners, StoredSignalType type,
            String token, String accountId) {
        if (token != null && !token.isBlank()) {
            owners.computeIfAbsent(new SignalKey(type, token), ignored -> new java.util.TreeSet<>()).add(accountId);
        }
    }

    private enum StoredSignalType { DEVICE, IP }

    private record SignalKey(StoredSignalType type, String token) implements Comparable<SignalKey> {
        @Override
        public int compareTo(SignalKey other) {
            int typeOrder = type.compareTo(other.type);
            return typeOrder != 0 ? typeOrder : token.compareTo(other.token);
        }
    }

    private record StoredSignal(StoredSignalType type, List<String> accountIds) {
    }

    /** Only device/IP hashes are persisted in MacroAuditEvidence for relationship explanation. */
    private static Track1ClusterDto.RelationshipExplanation relationship(List<MacroAuditEvidence> rows) {
        List<Track1ClusterDto.SharedSignal> signals = java.util.stream.Stream.of(
                shared(Track1ClusterDto.SharedSignalType.DEVICE_ID_HASH,
                        rows.stream().map(MacroAuditEvidence::getDeviceIdHash).toList()),
                shared(Track1ClusterDto.SharedSignalType.IP_HASH,
                        rows.stream().map(MacroAuditEvidence::getIpHash).toList())
        ).filter(Objects::nonNull).toList();
        long connections = signals.stream().mapToLong(Track1ClusterDto.SharedSignal::verifiedConnectionCount).sum();
        return new Track1ClusterDto.RelationshipExplanation(signals, connections,
                List.of("PAYMENT_HASH", "ADDRESS_HASH"), PRIORITY_SOURCE, NORMAL_SHARING_NOTICE, MEMBER_COVERAGE);
    }

    private static Track1ClusterDto.SharedSignal shared(Track1ClusterDto.SharedSignalType type, List<String> values) {
        Map<String, Long> counts = values.stream().filter(value -> value != null && !value.isBlank())
                .collect(java.util.stream.Collectors.groupingBy(value -> value, java.util.stream.Collectors.counting()));
        long members = counts.values().stream().filter(count -> count > 1).mapToLong(Long::longValue).sum();
        long connections = counts.values().stream().filter(count -> count > 1)
                .mapToLong(count -> count * (count - 1) / 2).sum();
        return members == 0 ? null : new Track1ClusterDto.SharedSignal(type, members, connections);
    }

    private static void validatePage(int page, int pageSize, String sort) {
        if (page < 0 || pageSize < 1 || pageSize > 100 || (long) page * pageSize > Integer.MAX_VALUE
                || !Set.of("priority-desc", "priority-asc", "recent").contains(sort)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid pagination or sort");
        }
    }
}
