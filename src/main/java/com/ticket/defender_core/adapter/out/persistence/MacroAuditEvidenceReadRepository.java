package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.MacroAuditEvidence;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class MacroAuditEvidenceReadRepository {
    private static final String CLUSTER_QUERY = """
            select new com.ticket.defender_core.adapter.out.persistence.StoredClusterAggregate(
                e.analysisRequestId, e.clusterId, min(e.riskScore), max(e.riskScore),
                min(e.analysisAlgorithm), max(e.analysisAlgorithm),
                min(e.analysisAlgorithmVersion), max(e.analysisAlgorithmVersion),
                min(e.analysisCompletedAt), max(e.analysisCompletedAt), count(e))
            from MacroAuditEvidence e
            where e.tenantId = :tenantId and e.internalEventId = :eventId
            group by e.analysisRequestId, e.clusterId
            """;

    private final EntityManager entityManager;

    public List<StoredClusterAggregate> findClusters(UUID tenantId, UUID eventId,
            String sort, int page, int pageSize) {
        String orderBy = switch (sort) {
            case "priority-desc" -> "order by max(e.riskScore) desc, e.analysisRequestId asc, e.clusterId asc";
            case "priority-asc" -> "order by max(e.riskScore) asc, e.analysisRequestId asc, e.clusterId asc";
            case "recent" -> "order by max(e.analysisCompletedAt) desc, e.analysisRequestId asc, e.clusterId asc";
            default -> throw new IllegalArgumentException("지원하지 않는 정렬 방식입니다.");
        };
        return entityManager.createQuery(CLUSTER_QUERY + orderBy, StoredClusterAggregate.class)
                .setParameter("tenantId", tenantId)
                .setParameter("eventId", eventId)
                .setFirstResult(Math.multiplyExact(page, pageSize))
                .setMaxResults(pageSize)
                .getResultList();
    }

    public long countClusters(UUID tenantId, UUID eventId) {
        Number count = (Number) entityManager.createNativeQuery("""
                select count(*) from (
                    select 1 from macro_audit_evidence
                    where tenant_id = :tenantId and internal_event_id = :eventId
                    group by analysis_request_id, cluster_id
                ) scoped_clusters
                """)
                .setParameter("tenantId", tenantId.toString())
                .setParameter("eventId", eventId.toString())
                .getSingleResult();
        return count.longValue();
    }

    public List<MacroAuditEvidence> findMembers(UUID tenantId, UUID eventId,
            String analysisRequestId, String clusterId) {
        return entityManager.createQuery("""
                select e from MacroAuditEvidence e
                where e.tenantId = :tenantId and e.internalEventId = :eventId
                    and e.analysisRequestId = :analysisRequestId and e.clusterId = :clusterId
                order by e.auditId asc
                """, MacroAuditEvidence.class)
                .setParameter("tenantId", tenantId)
                .setParameter("eventId", eventId)
                .setParameter("analysisRequestId", analysisRequestId)
                .setParameter("clusterId", clusterId)
                .getResultList();
    }
}
