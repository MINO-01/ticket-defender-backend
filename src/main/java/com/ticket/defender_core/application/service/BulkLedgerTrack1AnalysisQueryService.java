package com.ticket.defender_core.application.service;

import com.ticket.defender_core.security.AdminRole;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Read model for BL-06. It exposes provenance and failures, never ticket/reference HMACs. */
@Service
public class BulkLedgerTrack1AnalysisQueryService {
    private static final Set<AdminRole> ROLES=Set.of(AdminRole.TENANT_ADMIN,AdminRole.ANALYST,AdminRole.REVIEWER);
    private final CandidatePipelineAccess access; private final JdbcTemplate jdbc;
    public BulkLedgerTrack1AnalysisQueryService(CandidatePipelineAccess access,JdbcTemplate jdbc){this.access=access;this.jdbc=jdbc;}
    public List<View> list(UUID eventId,UUID performanceId){
        CandidatePipelineAccess.Scope s=access.event(eventId,ROLES);
        return jdbc.query("""
                select j.id,j.outbox_id,j.version_id,j.status,j.attempt_count,j.failure_code,j.run_id,r.analysis_request_id,r.execution_status,r.evaluation_status,r.policy_version,r.algorithm_version,r.completed_at
                from td_bulk_ledger_track1_analysis_jobs j left join track1_purchase_limit_runs r on r.id=j.run_id
                where j.tenant_id=? and j.event_id=? and j.performance_id=? order by j.created_at desc,j.id desc
                """,(rs,n)->view(rs),s.tenantId().toString(),s.eventId().toString(),performanceId.toString());
    }
    public View get(UUID eventId,UUID performanceId,UUID jobId){return list(eventId,performanceId).stream().filter(v->v.jobId().equals(jobId)).findFirst().orElseThrow(()->new CaseApiException(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND"));}
    private static View view(java.sql.ResultSet rs)throws java.sql.SQLException{return new View(uuid(rs.getString(1)),uuid(rs.getString(2)),uuid(rs.getString(3)),rs.getString(4),rs.getInt(5),rs.getString(6),nullable(rs.getString(7)),nullable(rs.getString(8)),rs.getString(9),rs.getString(10),rs.getString(11),rs.getString(12),rs.getTimestamp(13)==null?null:rs.getTimestamp(13).toLocalDateTime().atOffset(ZoneOffset.UTC));}
    private static UUID uuid(String v){return UUID.fromString(v);} private static UUID nullable(String v){return v==null?null:uuid(v);}
    public record View(UUID jobId,UUID outboxId,UUID versionId,String status,int attemptCount,String failureCode,UUID runId,UUID analysisRequestId,String executionStatus,String evaluationStatus,String policyVersion,String algorithmVersion,OffsetDateTime completedAt){public boolean retryable(){return "RETRYABLE".equals(status);}}
}
