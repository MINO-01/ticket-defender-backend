package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.api.FastApiAdapter;
import com.ticket.defender_core.adapter.out.api.dto.Track1BulkJobDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedWriter;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/** BL-07 bulk path. It never keeps a database transaction open while building S3 input or calling Data. */
@Service
public class BulkLedgerTrack1BulkWorker {
    private static final int PAGE = 1_000;
    private final JdbcTemplate jdbc; private final TransactionTemplate tx; private final BulkLedgerUploadStorage storage;
    private final FastApiAdapter data; private final SyntheticReservationImportGate gate; private final ObjectMapper json = JsonMapper.builder().build();
    private final boolean enabled; private final String policyBundle;
    public BulkLedgerTrack1BulkWorker(JdbcTemplate jdbc, TransactionTemplate tx, BulkLedgerUploadStorage storage, FastApiAdapter data,
            SyntheticReservationImportGate gate, @Value("${ticket-defender.bulk-ledger-track1.bulk.enabled:false}") boolean enabled,
            @Value("${ticket-defender.bulk-ledger-track1.bulk.policy-bundle-version:}") String policyBundle) {
        this.jdbc=jdbc; this.tx=tx; this.storage=storage; this.data=data; this.gate=gate; this.enabled=enabled; this.policyBundle=policyBundle;
    }
    @Scheduled(fixedDelayString="${ticket-defender.bulk-ledger-track1.bulk.worker-delay:PT30S}") public void scheduled(){ if(enabled) consumeOne(); }
    public void consumeOne() {
        Claim c=tx.execute(x->claim()); if(c==null) return;
        try {
            switch(c.status()) {
                case "PREPARING" -> prepare(c);
                case "REGISTERING" -> register(c);
                case "QUEUED", "RUNNING", "COLLECTING" -> pollOrCollect(c);
                case "RETRYABLE" -> retryData(c);
                default -> fail(c,"INVALID_JOB_STATE",false);
            }
        } catch (BulkLedgerUploadStorage.Unavailable | org.springframework.web.client.ResourceAccessException e) { retry(c,"DEPENDENCY_UNAVAILABLE"); }
        catch (org.springframework.web.client.HttpServerErrorException e) { retry(c,"DATA_UNAVAILABLE"); }
        catch (Blocked e) { fail(c,e.code,false); }
        catch (RuntimeException e) { fail(c,"BULK_ANALYSIS_FAILED",true); }
    }
    private Claim claim() {
        List<Claim> existing=jdbc.query("""
            select id,outbox_id,tenant_id,event_id,performance_id,ledger_version_id,status,data_job_id,fence_token
            from td_bulk_ledger_track1_bulk_jobs where status in ('PREPARING','REGISTERING','QUEUED','RUNNING','COLLECTING','RETRYABLE')
              and (lease_until is null or lease_until < utc_timestamp(6)) order by created_at limit 1 for update skip locked
            """,(rs,n)->new Claim(uuid(rs.getString(1)),uuid(rs.getString(2)),uuid(rs.getString(3)),uuid(rs.getString(4)),uuid(rs.getString(5)),uuid(rs.getString(6)),rs.getString(7),nullable(rs.getString(8)),rs.getLong(9)+1));
        if(!existing.isEmpty()) { Claim c=existing.getFirst(); jdbc.update("update td_bulk_ledger_track1_bulk_jobs set lease_owner=?,lease_until=date_add(utc_timestamp(6),interval 2 minute),fence_token=?,attempt_count=attempt_count+1,updated_at=utc_timestamp(6) where id=?",owner(c),c.fence(),c.id().toString()); return c; }
        List<Claim> rows=jdbc.query("""
            select id,tenant_id,event_id,performance_id,version_id from td_bulk_ledger_publication_outbox
            where event_type='LEDGER_PUBLISHED' and status in ('PENDING','RETRYABLE') order by created_at limit 1 for update skip locked
            """,(rs,n)->new Claim(null,uuid(rs.getString(1)),uuid(rs.getString(2)),uuid(rs.getString(3)),uuid(rs.getString(4)),uuid(rs.getString(5)),"PREPARING",null,1));
        if(rows.isEmpty()) return null; Claim raw=rows.getFirst(); UUID id=UUID.randomUUID();
        Claim created=new Claim(id,raw.outboxId(),raw.tenantId(),raw.eventId(),raw.performanceId(),raw.versionId(),"PREPARING",null,1);
        jdbc.update("update td_bulk_ledger_publication_outbox set status='PROCESSING',lease_owner=?,lease_until=date_add(utc_timestamp(6),interval 2 minute),fence_token=fence_token+1 where id=?",owner(created),raw.outboxId().toString());
        jdbc.update("""
            insert into td_bulk_ledger_track1_bulk_jobs(id,outbox_id,tenant_id,event_id,performance_id,ledger_version_id,status,lease_owner,lease_until,fence_token,created_at,updated_at)
            values(?,?,?,?,?,?, 'PREPARING',?,date_add(utc_timestamp(6),interval 2 minute),1,utc_timestamp(6),utc_timestamp(6))""",id.toString(),raw.outboxId().toString(),raw.tenantId().toString(),raw.eventId().toString(),raw.performanceId().toString(),raw.versionId().toString(),owner(created));
        jdbc.update("update td_bulk_ledger_publication_outbox set status='DELIVERED',lease_owner=null,lease_until=null where id=?",raw.outboxId().toString());
        return created;
    }
    private void prepare(Claim c) {
        gate.requireSyntheticScope(c.tenantId(),LocalDateTime.now(ZoneOffset.UTC));
        if(policyBundle==null||policyBundle.isBlank()||policyBundle.length()>128) throw new Blocked("POLICY_BUNDLE_UNAVAILABLE");
        Metadata m=metadata(c); Path path=null;
        try {
            path=Files.createTempFile("td-track1-bulk-", ".ndjson"); MessageDigest digest=MessageDigest.getInstance("SHA-256"); long count=writeNdjson(c,m,path,digest);
            if(count==0) throw new Blocked("NO_EFFECTIVE_TICKETS"); String hash=HexFormat.of().formatHex(digest.digest());
            String artifact="bl07-"+c.id().toString().replace("-","");
            try(InputStream in=Files.newInputStream(path)) { BulkLedgerUploadStorage.VerifiedObject stored=storage.putImmutable(artifact,in,Files.size(path),hash);
                tx.executeWithoutResult(x->updateArtifact(c,artifact,stored,m,count)); }
        } catch(Blocked e){throw e;} catch(Exception e){throw new RuntimeException(e);} finally { if(path!=null) try{Files.deleteIfExists(path);}catch(Exception ignored){} }
    }
    private Metadata metadata(Claim c) {
        List<Metadata> values=jdbc.query("""
          with recursive l as (select id,base_version_id,generation_no,revision_no,source_as_of from td_bulk_ledger_versions where id=? and tenant_id=? and event_id=? and performance_id=? and status='PUBLISHED'
            union all select p.id,p.base_version_id,p.generation_no,p.revision_no,p.source_as_of from td_bulk_ledger_versions p join l on l.base_version_id=p.id where p.status='PUBLISHED' and p.generation_no=l.generation_no and p.revision_no=l.revision_no-1)
          select max(case when revision_no=0 then source_as_of end),min(generation_no),max(revision_no),count(*) from l
          """,(rs,n)->new Metadata(local(rs.getTimestamp(1)),rs.getLong(2),rs.getLong(3),rs.getInt(4),null,null),c.versionId().toString(),c.tenantId().toString(),c.eventId().toString(),c.performanceId().toString());
        if(values.size()!=1||values.getFirst().sourceAsOf()==null||values.getFirst().lineageCount()<1) throw new Blocked("LEDGER_LINEAGE_INVALID");
        String external=jdbc.queryForObject("select external_event_ref from events where id=? and tenant_id=?",String.class,c.eventId().toString(),c.tenantId().toString());
        if(external==null||external.isBlank()) throw new Blocked("LEDGER_EVENT_SCOPE_INVALID"); Metadata m=values.getFirst(); return new Metadata(m.sourceAsOf(),m.generation(),m.revision(),m.lineageCount(),external,null);
    }
    private long writeNdjson(Claim c, Metadata m, Path path, MessageDigest digest) throws Exception {
        final long[] count={0}; final String[] source={null}, key={null};
        try(BufferedWriter w=Files.newBufferedWriter(path,StandardCharsets.UTF_8)) {
            jdbc.query("""
              with recursive l as (select id,base_version_id,generation_no,revision_no from td_bulk_ledger_versions where id=?
                union all select p.id,p.base_version_id,p.generation_no,p.revision_no from td_bulk_ledger_versions p join l on l.base_version_id=p.id where p.status='PUBLISHED' and p.generation_no=l.generation_no and p.revision_no=l.revision_no-1),
              ranked as (select tv.*,row_number() over(partition by tv.ticket_reference_hmac order by l.revision_no desc) rn from td_bulk_ledger_ticket_versions tv join l on l.id=tv.version_id)
              select r.ticket_reference_hmac,r.reservation_reference_hmac,r.account_reference_hmac,r.purchased_at,r.ticket_quantity,r.ticket_status,r.source_version,
                     s.payment_hmac,s.address_hmac,s.device_hmac,s.ip_hmac,s.hmac_key_version,s.source_version,s.hmac_purpose
              from ranked r join td_bulk_ledger_track1_ticket_signals s on s.version_id=r.version_id and s.ticket_reference_hmac=r.ticket_reference_hmac
              where r.rn=1 order by r.ticket_reference_hmac
              """,rs->{
                try {
                    String sourceVersion=rs.getString(7), keyVersion=rs.getString(12);
                    if(!"TRACK1_CONNECTION".equals(rs.getString(14))||sourceVersion==null||!sourceVersion.equals(rs.getString(13))||keyVersion==null||
                            (rs.getString(8)==null&&rs.getString(9)==null&&rs.getString(10)==null&&rs.getString(11)==null)) throw new Blocked("LEDGER_SIGNAL_OR_PROVENANCE_INVALID");
                    if(source[0]!=null&&!source[0].equals(sourceVersion)||key[0]!=null&&!key[0].equals(keyVersion)) throw new Blocked("LEDGER_SOURCE_OR_KEY_MIXED"); source[0]=sourceVersion; key[0]=keyVersion;
                    Map<String,Object> row=new LinkedHashMap<>(); row.put("accountId",rs.getString(3)); row.put("reservationNo",rs.getString(2)); row.put("eventId",m.externalEvent()); row.put("performanceId",c.performanceId().toString());
                    row.put("purchasedAt",rs.getTimestamp(4).toLocalDateTime().atOffset(ZoneOffset.UTC).toString()); row.put("effectiveTicketQuantity",rs.getInt(5)); row.put("paymentHash",rs.getString(8)); row.put("addressHash",rs.getString(9)); row.put("deviceIdHash",rs.getString(10)); row.put("ipHash",rs.getString(11));
                    row.put("quantityInputType","INDIVIDUAL_TICKET"); row.put("ticketReference",rs.getString(1)); row.put("ticketStatus",rs.getString(6)); row.put("sourceVersion",sourceVersion); row.put("sourceAsOf",m.sourceAsOf().atOffset(ZoneOffset.UTC).toString());
                    byte[] bytes=(json.writeValueAsString(row)+"\n").getBytes(StandardCharsets.UTF_8); w.write(new String(bytes,StandardCharsets.UTF_8)); digest.update(bytes); count[0]++;
                } catch(Blocked e){throw e;} catch(Exception e){throw new RuntimeException(e);}
              },c.versionId().toString());
        }
        if(source[0]==null||key[0]==null) throw new Blocked("NO_EFFECTIVE_TICKETS");
        tx.executeWithoutResult(x->jdbc.update("update td_bulk_ledger_track1_bulk_jobs set source_version=?,hmac_key_version=? where id=? and fence_token=?",source[0],key[0],c.id().toString(),c.fence()));
        return count[0];
    }
    private void updateArtifact(Claim c,String artifact,BulkLedgerUploadStorage.VerifiedObject stored,Metadata m,long count) { require(c); jdbc.update("""
        update td_bulk_ledger_track1_bulk_jobs set artifact_id=?,object_key=?,object_version=?,artifact_sha256=?,record_count=?,source_as_of=?,policy_version=?,status='REGISTERING',lease_until=null,updated_at=utc_timestamp(6) where id=? and fence_token=?
        """,artifact,artifact,stored.versionId(),stored.sha256(),count,Timestamp.valueOf(m.sourceAsOf()),policyBundle,c.id().toString(),c.fence()); }
    private void register(Claim c) {
        Job j=job(c); Track1BulkJobDto.Manifest manifest=new Track1BulkJobDto.Manifest(j.artifactId(),j.objectKey(),j.objectVersion(),j.sha256(),j.recordCount(),"track1-limit-ndjson-v1",c.tenantId().toString(),j.externalEvent(),c.versionId().toString(),Long.toString(j.generation()),Long.toString(j.revision()),j.sourceVersion(),j.sourceAsOf().atOffset(ZoneOffset.UTC),"TRACK1_CONNECTION",j.keyVersion(),j.policyVersion(),List.of(scope(c,j)));
        Track1BulkJobDto.Accepted accepted=data.createBulkJob(new Track1BulkJobDto.Create(manifest),CandidatePipelineSupport.sha256("BL07",c.outboxId().toString(),j.sha256()));
        tx.executeWithoutResult(x->{require(c);jdbc.update("update td_bulk_ledger_track1_bulk_jobs set data_job_id=?,data_status=?,status='QUEUED',lease_until=null,updated_at=utc_timestamp(6) where id=? and fence_token=?",accepted.jobId().toString(),accepted.status(),c.id().toString(),c.fence());});
    }
    private Track1BulkJobDto.Scope scope(Claim c,Job j) { return new Track1BulkJobDto.Scope(c.eventId(),j.externalEvent(),c.performanceId(),"POLICY_NOT_CONFIRMED",null,"INCOMPLETE",j.sourceVersion(),j.sourceAsOf().atOffset(ZoneOffset.UTC),"NOT_CONFIRMED","DEDUPLICATED","BULK_POLICY_EVALUATION_NOT_CONFIRMED"); }
    private void pollOrCollect(Claim c) {
        if(c.dataJobId()==null) throw new Blocked("DATA_JOB_MISSING"); Track1BulkJobDto.Status status=data.bulkJobStatus(c.dataJobId());
        if("COMPLETED".equals(status.status())) { collect(c,status); return; }
        if(Set.of("FAILED","INPUT_REJECTED","CANCELLED","INTERRUPTED").contains(status.status())) { retry(c,status.failureCode()==null?"DATA_"+status.status():status.failureCode()); return; }
        tx.executeWithoutResult(x->{require(c);jdbc.update("update td_bulk_ledger_track1_bulk_jobs set data_status=?,status=?,lease_until=null,updated_at=utc_timestamp(6) where id=? and fence_token=?",status.status(),"RUNNING".equals(status.status())?"RUNNING":"QUEUED",c.id().toString(),c.fence());});
    }
    private void collect(Claim c,Track1BulkJobDto.Status status) {
        Job j=job(c); Track1BulkJobDto.Results page=data.bulkResults(c.dataJobId(),j.cursor());
        if(!"COMPLETED".equals(page.status())||!"NOT_IMPLEMENTED".equals(page.sharedSignalObservationsStatus())||!"NOT_IMPLEMENTED".equals(page.purchaseLimitEvaluationsStatus())) throw new Blocked("DATA_RESULT_CONTRACT_INVALID");
        for(Track1BulkJobDto.Cluster cluster:page.clusters()) {
            tx.executeWithoutResult(x->{require(c);saveCluster(c,j.externalEvent(),cluster);heartbeat(c);});
            if(cluster.hasMoreMembers()) { String cursor=null; do {
                // Data I/O stays outside the short transaction that persists this one cursor page.
                Track1BulkJobDto.Members members=data.bulkMembers(c.dataJobId(),cluster.clusterId(),cursor);
                tx.executeWithoutResult(x->{require(c);members.members().forEach(m->saveMember(c,j.externalEvent(),cluster.clusterId(),m));heartbeat(c);});
                cursor=members.nextCursor();
            } while(cursor!=null); }
        }
        tx.executeWithoutResult(x->{require(c);String next=page.nextCursor(); jdbc.update("update td_bulk_ledger_track1_bulk_jobs set data_status='COMPLETED',algorithm_version=?,shared_signal_observations_status=?,purchase_limit_evaluations_status=?,result_cursor=?,status=?,lease_until=null,completed_at=case when ? is null then utc_timestamp(6) else completed_at end,updated_at=utc_timestamp(6) where id=? and fence_token=?",page.algorithmVersion(),page.sharedSignalObservationsStatus(),page.purchaseLimitEvaluationsStatus(),next,next==null?"COMPLETED":"COLLECTING",next,c.id().toString(),c.fence());});
    }
    private void saveCluster(Claim c,String externalEvent,Track1BulkJobDto.Cluster cluster) { jdbc.update("insert into td_bulk_ledger_track1_bulk_clusters(job_id,cluster_id,risk_score,member_count,payment_hashes_json,address_hashes_json,device_hashes_json,ip_hashes_json) values(?,?,?,?,cast(? as json),cast(? as json),cast(? as json),cast(? as json)) on duplicate key update risk_score=values(risk_score),member_count=values(member_count)",c.id().toString(),cluster.clusterId(),cluster.riskScore(),cluster.memberCount(),asJson(cluster.paymentHashes()),asJson(cluster.addressHashes()),asJson(cluster.deviceIdHashes()),asJson(cluster.ipHashes())); cluster.members().forEach(m->saveMember(c,externalEvent,cluster.clusterId(),m)); }
    private void saveMember(Claim c,String externalEvent,String cluster,Track1BulkJobDto.Member member){ if(!externalEvent.equals(member.eventId())||!member.accountId().matches("[0-9a-f]{64}")||!member.reservationNo().matches("[0-9a-f]{64}")) throw new Blocked("DATA_MEMBER_SCOPE_INVALID"); jdbc.update("insert ignore into td_bulk_ledger_track1_bulk_members(job_id,cluster_id,account_reference_hmac,reservation_reference_hmac) values(?,?,?,?)",c.id().toString(),cluster,member.accountId(),member.reservationNo()); }
    private void retryData(Claim c){ if(c.dataJobId()==null) throw new Blocked("DATA_JOB_MISSING"); Track1BulkJobDto.Accepted a=data.retryBulkJob(c.dataJobId()); tx.executeWithoutResult(x->{require(c);jdbc.update("update td_bulk_ledger_track1_bulk_jobs set status='QUEUED',data_status=?,failure_code=null,lease_until=null,updated_at=utc_timestamp(6) where id=? and fence_token=?",a.status(),c.id().toString(),c.fence());}); }
    private void retry(Claim c,String code){tx.executeWithoutResult(x->{require(c); Integer attempts=jdbc.queryForObject("select attempt_count from td_bulk_ledger_track1_bulk_jobs where id=?",Integer.class,c.id().toString()); jdbc.update("update td_bulk_ledger_track1_bulk_jobs set status=?,failure_code=?,lease_until=null,updated_at=utc_timestamp(6) where id=? and fence_token=?",attempts!=null&&attempts>=3?"FAILED":"RETRYABLE",code,c.id().toString(),c.fence());});}
    private void fail(Claim c,String code,boolean retryable){ if(retryable){retry(c,code);return;} tx.executeWithoutResult(x->{require(c);jdbc.update("update td_bulk_ledger_track1_bulk_jobs set status='BLOCKED',failure_code=?,lease_until=null,updated_at=utc_timestamp(6) where id=? and fence_token=?",code,c.id().toString(),c.fence());}); }
    private Job job(Claim c){ return jdbc.queryForObject("select j.artifact_id,j.object_key,j.object_version,j.artifact_sha256,j.record_count,j.source_version,j.source_as_of,j.hmac_key_version,j.policy_version,v.generation_no,v.revision_no,e.external_event_ref,j.result_cursor from td_bulk_ledger_track1_bulk_jobs j join td_bulk_ledger_versions v on v.id=j.ledger_version_id join events e on e.id=j.event_id where j.id=?",(rs,n)->new Job(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getLong(5),rs.getString(6),local(rs.getTimestamp(7)),rs.getString(8),rs.getString(9),rs.getLong(10),rs.getLong(11),rs.getString(12),rs.getString(13)),c.id().toString()); }
    private void require(Claim c){Integer ok=jdbc.queryForObject("select count(*) from td_bulk_ledger_track1_bulk_jobs where id=? and fence_token=? and lease_owner=?",Integer.class,c.id().toString(),c.fence(),owner(c));if(ok==null||ok!=1)throw new Blocked("WORKER_FENCE_LOST");}
    private void heartbeat(Claim c){jdbc.update("update td_bulk_ledger_track1_bulk_jobs set lease_until=date_add(utc_timestamp(6),interval 2 minute),updated_at=utc_timestamp(6) where id=? and fence_token=? and lease_owner=?",c.id().toString(),c.fence(),owner(c));}
    private static String owner(Claim c){return "bl07-"+c.id();} private static UUID uuid(String s){return UUID.fromString(s);} private static UUID nullable(String s){return s==null?null:uuid(s);} private static LocalDateTime local(Timestamp t){return t==null?null:t.toLocalDateTime();} private String asJson(Object v){try{return json.writeValueAsString(v);}catch(Exception e){throw new IllegalStateException(e);}}
    private record Claim(UUID id,UUID outboxId,UUID tenantId,UUID eventId,UUID performanceId,UUID versionId,String status,UUID dataJobId,long fence){} private record Metadata(LocalDateTime sourceAsOf,long generation,long revision,int lineageCount,String externalEvent,String ignored){} private record Job(String artifactId,String objectKey,String objectVersion,String sha256,long recordCount,String sourceVersion,LocalDateTime sourceAsOf,String keyVersion,String policyVersion,long generation,long revision,String externalEvent,String cursor){} private static final class Blocked extends RuntimeException{final String code;Blocked(String c){code=c;}}
}
