package com.ticket.defender_core.adapter.out.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class MultiEventCandidateCaseSourceReader {
    private final JdbcTemplate jdbc;

    public Optional<CompletedCandidate> findCompletedCandidate(UUID tenantId, UUID runId, String candidateId) {
        List<CompletedCandidate> rows = jdbc.query("""
                select r.completed_at, r.analysis_algorithm_version
                from td_multi_event_investigation_runs r
                join td_multi_event_investigation_candidates c on c.run_id = r.id
                where r.tenant_id = ? and r.id = ? and c.candidate_id = ?
                  and r.status = 'COMPLETED' and r.completed_at is not null
                  and r.analysis_algorithm_version is not null
                """, (rs, row) -> new CompletedCandidate(
                rs.getTimestamp("completed_at").toLocalDateTime(),
                rs.getString("analysis_algorithm_version")), tenantId.toString(), runId.toString(), candidateId);
        return rows.stream().findFirst();
    }

    public List<UUID> findEventIds(UUID tenantId, UUID runId) {
        return jdbc.query("""
                select distinct event_id from td_multi_event_investigation_scopes
                where tenant_id = ? and run_id = ? order by event_id
                """, (rs, row) -> UUID.fromString(rs.getString("event_id")),
                tenantId.toString(), runId.toString());
    }

    public record CompletedCandidate(LocalDateTime completedAt, String algorithmVersion) { }
}
