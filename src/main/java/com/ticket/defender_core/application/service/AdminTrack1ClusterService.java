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
                .map(row -> new Track1ClusterDto.Member(row.getAuditId()))
                .toList();
        return new Track1ClusterDto.Detail(event, cluster, members, relationship(rows), COVERAGE);
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
