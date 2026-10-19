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
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminTrack1ClusterService {
    private static final Set<AdminRole> VIEW_ROLES = Set.of(
            AdminRole.TENANT_ADMIN, AdminRole.ANALYST, AdminRole.REVIEWER, AdminRole.APPROVER);
    private static final String COVERAGE = "STORED_EVIDENCE_ONLY";

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
        return new Track1ClusterDto.Detail(event, cluster, members, COVERAGE);
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

    private static void validatePage(int page, int pageSize, String sort) {
        if (page < 0 || pageSize < 1 || pageSize > 100 || (long) page * pageSize > Integer.MAX_VALUE
                || !Set.of("priority-desc", "priority-asc", "recent").contains(sort)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid pagination or sort");
        }
    }
}
