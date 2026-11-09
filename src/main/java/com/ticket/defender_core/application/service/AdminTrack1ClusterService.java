package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.Track1ClusterDto;
import com.ticket.defender_core.adapter.out.persistence.MacroAuditEvidenceReadRepository;
import com.ticket.defender_core.adapter.out.persistence.StoredClusterAggregate;
import com.ticket.defender_core.adapter.out.persistence.TenantEventRepository;
import com.ticket.defender_core.adapter.out.persistence.VerifiedPerformanceRepository;
import com.ticket.defender_core.domain.AccessRecordStatus;
import com.ticket.defender_core.domain.MacroAuditEvidence;
import com.ticket.defender_core.domain.TenantEvent;
import com.ticket.defender_core.domain.VerifiedPerformance;
import com.ticket.defender_core.security.AdminAccessService;
import com.ticket.defender_core.security.AdminActor;
import com.ticket.defender_core.security.AdminRole;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.ZoneOffset;
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
    private static final String PRIORITY_SOURCE = "STORED_LOUVAIN_RISK_SCORE";
    private static final String NORMAL_SHARING_NOTICE = "공동 이용 기기 또는 네트워크의 정상 공유 가능성을 함께 검토해야 하며, "
            + "이 관계 정보만으로 동일인·조직 또는 부정행위를 확정할 수 없습니다.";

    private final AdminAccessService access;
    private final TenantEventRepository events;
    private final MacroAuditEvidenceReadRepository evidence;
    private final VerifiedPerformanceRepository performances;
    private final Track1LedgerSeatLookupService ledgerSeats;

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
        return detail(eventId, analysisRequestId, clusterId, null, null);
    }

    @Transactional(readOnly = true)
    public Track1ClusterDto.Detail detail(UUID eventId, String analysisRequestId, String clusterId,
            UUID performanceId, String sourceVersion) {
        validateLedgerScope(performanceId, sourceVersion);
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
        DetailMembers detailMembers = members(actor, rows, performanceId, sourceVersion);
        return new Track1ClusterDto.Detail(event, cluster, detailMembers.members(), relationship(rows),
                graph(analysisRequestId, clusterId, rows), COVERAGE, detailMembers.verificationContext());
    }

    private DetailMembers members(AdminActor actor, List<MacroAuditEvidence> rows,
            UUID performanceId, String sourceVersion) {
        if (performanceId == null && sourceVersion == null)
            return new DetailMembers(rows.stream().map(row -> new Track1ClusterDto.Member(row.getAuditId(), null)).toList(),
                    new Track1ClusterDto.VerificationContext(Track1ClusterDto.VerificationStatus.NOT_REQUESTED,
                            null, null, null));
        VerifiedPerformance performance = performances.findById(performanceId)
                .filter(found -> actor.tenantId().equals(found.getTenantId()) && actor.eventId().equals(found.getEventId())
                        && found.getStatus() == VerifiedPerformance.Status.ACTIVE)
                .orElseThrow(AdminTrack1ClusterService::notFound);
        Track1LedgerSeatLookupService.Result result = ledgerSeats.lookup(actor.tenantId(), actor.eventId(),
                performance.getId(), sourceVersion, null, null, rows);
        List<Track1ClusterDto.Member> members = result.seats().stream().map(seat -> new Track1ClusterDto.Member(seat.auditId(),
                new Track1ClusterDto.LedgerSeat(Track1ClusterDto.LedgerSeatStatus.valueOf(seat.status().name()),
                        seat.zone(), seat.row(), seat.seat(), seat.seats().stream()
                                .map(value -> new Track1ClusterDto.Seat(value.zone(), value.row(), value.seat()))
                                .toList()))).toList();
        Track1ClusterDto.VerificationContext context = result.ledgerAvailable()
                ? new Track1ClusterDto.VerificationContext(Track1ClusterDto.VerificationStatus.CURRENT_COMPLETE,
                        Track1ClusterDto.VerificationBasis.RESERVATION_REFERENCE, performanceId, sourceVersion)
                : new Track1ClusterDto.VerificationContext(Track1ClusterDto.VerificationStatus.LEDGER_UNAVAILABLE,
                        null, performanceId, sourceVersion);
        return new DetailMembers(members, context);
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

    /** 2개 이상 계정이 공유한 저장 신호만 간선으로 만듭니다. 원본 그래프 전체를 복원한다는 뜻이 아닙니다. */
    private static Track1ClusterDto.Graph graph(String analysisRequestId, String clusterId, List<MacroAuditEvidence> rows) {
        Map<String, Track1ClusterDto.GraphNode> nodes = new java.util.LinkedHashMap<>();
        List<Track1ClusterDto.GraphEdge> edges = new java.util.ArrayList<>();
        graphSignal(analysisRequestId, clusterId, rows, "DEVICE", MacroAuditEvidence::getDeviceIdHash, nodes, edges);
        graphSignal(analysisRequestId, clusterId, rows, "IP", MacroAuditEvidence::getIpHash, nodes, edges);
        return new Track1ClusterDto.Graph(List.copyOf(nodes.values()), List.copyOf(edges));
    }

    private static void graphSignal(String analysisRequestId, String clusterId, List<MacroAuditEvidence> rows, String kind,
            java.util.function.Function<MacroAuditEvidence, String> signal, Map<String, Track1ClusterDto.GraphNode> nodes,
            List<Track1ClusterDto.GraphEdge> edges) {
        Map<String, java.util.Set<String>> accounts = new java.util.TreeMap<>();
        rows.stream().filter(row -> signal.apply(row) != null && !signal.apply(row).isBlank())
                .forEach(row -> accounts.computeIfAbsent(signal.apply(row), ignored -> new java.util.TreeSet<>()).add(row.getAccountId()));
        int[] signalNumber = {0};
        accounts.forEach((value, owners) -> {
            if (owners.size() < 2) return;
            signalNumber[0]++;
            String signalId = opaque(analysisRequestId, clusterId, kind, value);
            nodes.put(signalId, new Track1ClusterDto.GraphNode(signalId, "signal", kind + " " + signalNumber[0]));
            owners.forEach(owner -> {
                String accountId = opaque(analysisRequestId, clusterId, "ACCOUNT", owner);
                nodes.computeIfAbsent(accountId, id -> new Track1ClusterDto.GraphNode(id, "account", "계정 " + id.substring(0, 6)));
                edges.add(new Track1ClusterDto.GraphEdge(opaque(analysisRequestId, clusterId, "EDGE", accountId + signalId), accountId, signalId, kind));
            });
        });
    }

    private static String opaque(String analysisRequestId, String clusterId, String kind, String value) {
        return CandidatePipelineSupport.sha256("TRACK1_GRAPH", analysisRequestId, clusterId, kind, value).substring(0, 24);
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

    private static void validateLedgerScope(UUID performanceId, String sourceVersion) {
        if ((performanceId == null) != (sourceVersion == null)
                || (sourceVersion != null && (sourceVersion.isBlank() || sourceVersion.length() > 128)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid ledger scope");
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found");
    }

    private record DetailMembers(List<Track1ClusterDto.Member> members,
                                 Track1ClusterDto.VerificationContext verificationContext) { }
}
