package com.ticket.defender_core.application.service;

import com.ticket.defender_core.security.AdminRole;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.ZoneOffset;
import java.util.*;

/** Tenant/event-scoped read model. It never returns HMACs or S3 references. */
@Service
public class BulkLedgerTrack1BulkQueryService {
    private static final Set<AdminRole> ROLES=Set.of(AdminRole.TENANT_ADMIN,AdminRole.ANALYST,AdminRole.REVIEWER,AdminRole.APPROVER);
    private final CandidatePipelineAccess access; private final JdbcTemplate jdbc;
    public BulkLedgerTrack1BulkQueryService(CandidatePipelineAccess access,JdbcTemplate jdbc){this.access=access;this.jdbc=jdbc;}
    public List<Job> list(UUID eventId,UUID performanceId){var s=access.event(eventId,ROLES);return jdbc.query("select id,ledger_version_id,data_job_id,status,data_status,failure_code,algorithm_version,shared_signal_observations_status,purchase_limit_evaluations_status,completed_at from td_bulk_ledger_track1_bulk_jobs where tenant_id=? and event_id=? and performance_id=? order by created_at desc",(rs,n)->new Job(uuid(rs.getString(1)),uuid(rs.getString(2)),nullable(rs.getString(3)),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9),rs.getTimestamp(10)==null?null:rs.getTimestamp(10).toLocalDateTime().atOffset(ZoneOffset.UTC)),s.tenantId().toString(),eventId.toString(),performanceId.toString());}
    public Page clusters(UUID eventId,UUID performanceId,UUID jobId,int page,int size){if(page<0||size<1||size>100)throw new CaseApiException(HttpStatus.BAD_REQUEST,"INVALID_PAGE");Job j=find(eventId,performanceId,jobId);List<Cluster> rows=jdbc.query("select cluster_id,risk_score,member_count from td_bulk_ledger_track1_bulk_clusters where job_id=? order by risk_score desc,cluster_id limit ? offset ?",(rs,n)->new Cluster(rs.getString(1),rs.getDouble(2),rs.getInt(3)),jobId.toString(),size+1,page*size);boolean more=rows.size()>size;if(more)rows=rows.subList(0,size);return new Page(j,rows,page,size,more);}
    public Members members(UUID eventId,UUID performanceId,UUID jobId,String clusterId,int page,int size){if(page<0||size<1||size>500||clusterId==null||clusterId.isBlank())throw new CaseApiException(HttpStatus.BAD_REQUEST,"INVALID_PAGE");find(eventId,performanceId,jobId);int count=jdbc.queryForObject("select count(*) from td_bulk_ledger_track1_bulk_clusters where job_id=? and cluster_id=?",Integer.class,jobId.toString(),clusterId);if(count!=1)throw new CaseApiException(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND");List<Member> rows=jdbc.query("select account_reference_hmac,reservation_reference_hmac from td_bulk_ledger_track1_bulk_members where job_id=? and cluster_id=? order by account_reference_hmac,reservation_reference_hmac limit ? offset ?",(rs,n)->new Member(opaque(jobId,clusterId,rs.getString(1),rs.getString(2))),jobId.toString(),clusterId,size+1,page*size);boolean more=rows.size()>size;if(more)rows=rows.subList(0,size);return new Members(clusterId,rows,page,size,more);}
    private Job find(UUID event,UUID performance,UUID job){return list(event,performance).stream().filter(x->x.jobId().equals(job)).findFirst().orElseThrow(()->new CaseApiException(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND"));}
    private static String opaque(UUID job,String cluster,String account,String reservation){return CandidatePipelineSupport.sha256("BL07_MEMBER",job.toString(),cluster,account,reservation).substring(0,24);}private static UUID uuid(String x){return UUID.fromString(x);}private static UUID nullable(String x){return x==null?null:uuid(x);}
    public record Job(UUID jobId,UUID ledgerVersionId,UUID dataJobId,String status,String dataStatus,String failureCode,String algorithmVersion,String sharedSignalObservationsStatus,String purchaseLimitEvaluationsStatus,java.time.OffsetDateTime completedAt){public boolean retryable(){return "RETRYABLE".equals(status)||"FAILED".equals(status);}} public record Cluster(String clusterId,double riskScore,int memberCount){} public record Member(String memberId){} public record Page(Job job,List<Cluster> items,int page,int pageSize,boolean hasNext){} public record Members(String clusterId,List<Member> items,int page,int pageSize,boolean hasNext){}
}
