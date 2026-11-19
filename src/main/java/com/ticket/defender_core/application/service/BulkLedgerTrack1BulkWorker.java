package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.api.FastApiAdapter;
import com.ticket.defender_core.adapter.out.api.dto.Track1BulkJobDto;
import com.ticket.defender_core.adapter.out.persistence.PurchaseLimitPolicyRepository;
import com.ticket.defender_core.domain.PurchaseLimitPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import jakarta.annotation.PostConstruct;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedWriter;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.sql.ResultSet;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/** BL-07 bulk path. It never keeps a database transaction open while building S3 input or calling Data. */
@Service
public class BulkLedgerTrack1BulkWorker {
    private static final int PAGE = 1_000;
    /** Connector/J's forward-only streaming mode; do not replace with a positive fetch size without useCursorFetch. */
    static final int MYSQL_STREAMING_FETCH_SIZE = Integer.MIN_VALUE;
    private final JdbcTemplate jdbc; private final TransactionTemplate tx; private final BulkLedgerUploadStorage storage;
    private final FastApiAdapter data; private final SyntheticReservationImportGate gate; private final PurchaseLimitPolicyRepository policies; private final ObjectMapper json = JsonMapper.builder().build();
    private final boolean enabled; private final boolean legacyEnabled; private final String policyBundle;
    public BulkLedgerTrack1BulkWorker(JdbcTemplate jdbc, TransactionTemplate tx, BulkLedgerUploadStorage storage, FastApiAdapter data,
            SyntheticReservationImportGate gate, PurchaseLimitPolicyRepository policies, @Value("${ticket-defender.bulk-ledger-track1.bulk.enabled:false}") boolean enabled,
            @Value("${ticket-defender.bulk-ledger-track1.enabled:false}") boolean legacyEnabled,
            @Value("${ticket-defender.bulk-ledger-track1.bulk.policy-bundle-version:}") String policyBundle) {
        this.jdbc=jdbc; this.tx=tx; this.storage=storage; this.data=data; this.gate=gate; this.policies=policies; this.enabled=enabled; this.legacyEnabled=legacyEnabled; this.policyBundle=policyBundle;
    }
    @PostConstruct void rejectCompetingConsumers() { if(enabled && legacyEnabled) throw new IllegalStateException("Only one bulk-ledger Track 1 consumer may be enabled"); }
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
        } catch (BulkLedgerUploadStorage.Unavailable | org.springframework.web.client.ResourceAccessException e) { transientFailure(c,"DEPENDENCY_UNAVAILABLE"); }
        catch (org.springframework.web.client.HttpServerErrorException e) { transientFailure(c,"DATA_UNAVAILABLE"); }
        catch (Blocked e) { fail(c,e.code,false); }
        catch (RuntimeException e) { transientFailure(c,"BULK_ANALYSIS_FAILED"); }
    }
    private Claim claim() {
        List<Claim> existing=jdbc.query("""
            select id,outbox_id,tenant_id,event_id,performance_id,ledger_version_id,status,data_job_id,fence_token
            from td_bulk_ledger_track1_bulk_jobs where status in ('PREPARING','REGISTERING','QUEUED','RUNNING','COLLECTING','RETRYABLE')
              and (lease_until is null or lease_until < utc_timestamp(6)) order by created_at limit 1 for update skip locked
            """,(rs,n)->new Claim(uuid(rs.getString(1)),uuid(rs.getString(2)),uuid(rs.getString(3)),uuid(rs.getString(4)),uuid(rs.getString(5)),uuid(rs.getString(6)),rs.getString(7),nullable(rs.getString(8)),rs.getLong(9)+1));
        if(!existing.isEmpty()) { Claim c=existing.getFirst(); jdbc.update("update td_bulk_ledger_track1_bulk_jobs set lease_owner=?,lease_until=date_add(utc_timestamp(6),interval 30 minute),fence_token=?,updated_at=utc_timestamp(6) where id=?",owner(c),c.fence(),c.id().toString()); return c; }
        List<Claim> rows=jdbc.query("""
            select id,tenant_id,event_id,performance_id,version_id from td_bulk_ledger_publication_outbox
            where event_type='LEDGER_PUBLISHED' and status in ('PENDING','RETRYABLE') order by created_at limit 1 for update skip locked
            """,(rs,n)->new Claim(null,uuid(rs.getString(1)),uuid(rs.getString(2)),uuid(rs.getString(3)),uuid(rs.getString(4)),uuid(rs.getString(5)),"PREPARING",null,1));
        if(rows.isEmpty()) return null; Claim raw=rows.getFirst(); UUID id=UUID.randomUUID();
        Claim created=new Claim(id,raw.outboxId(),raw.tenantId(),raw.eventId(),raw.performanceId(),raw.versionId(),"PREPARING",null,1);
        jdbc.update("update td_bulk_ledger_publication_outbox set status='PROCESSING',lease_owner=?,lease_until=date_add(utc_timestamp(6),interval 30 minute),fence_token=fence_token+1 where id=?",owner(created),raw.outboxId().toString());
        jdbc.update("""
            insert into td_bulk_ledger_track1_bulk_jobs(id,outbox_id,tenant_id,event_id,performance_id,ledger_version_id,status,lease_owner,lease_until,fence_token,created_at,updated_at)
            values(?,?,?,?,?,?, 'PREPARING',?,date_add(utc_timestamp(6),interval 30 minute),1,utc_timestamp(6),utc_timestamp(6))""",id.toString(),raw.outboxId().toString(),raw.tenantId().toString(),raw.eventId().toString(),raw.performanceId().toString(),raw.versionId().toString(),owner(created));
        jdbc.update("update td_bulk_ledger_publication_outbox set status='DELIVERED',lease_owner=null,lease_until=null where id=?",raw.outboxId().toString());
        return created;
    }
    private void prepare(Claim c) {
        gate.requireSyntheticScope(c.tenantId(),LocalDateTime.now(ZoneOffset.UTC));
        if(policyBundle==null||policyBundle.isBlank()||policyBundle.length()>128) throw new Blocked("POLICY_BUNDLE_UNAVAILABLE");
        Metadata m=metadata(c); snapshot(c,m); Path path=null;
        try {
            path=Files.createTempFile("td-track1-bulk-", ".ndjson"); MessageDigest digest=MessageDigest.getInstance("SHA-256"); long count=writeNdjson(c,m,path,digest);
            if(count==0) throw new Blocked("NO_EFFECTIVE_TICKETS"); String hash=HexFormat.of().formatHex(digest.digest());
            String artifact="bl07-"+c.id().toString().replace("-","");
            try(InputStream in=Files.newInputStream(path)) { BulkLedgerUploadStorage.VerifiedObject stored=storage.putImmutable(artifact,in,Files.size(path),hash,()->heartbeat(c));
                if(!manifestObjectVersionCompatible(stored.versionId())) throw new Blocked("OBJECT_VERSION_CONTRACT_INCOMPATIBLE");
                tx.executeWithoutResult(x->updateArtifact(c,artifact,stored,m,count)); }
        } catch(Blocked e){throw e;} catch(BulkLedgerUploadStorage.InvalidObject | BulkLedgerUploadStorage.ObjectChanged e){throw new Blocked("BULK_ARTIFACT_CONTRACT_INVALID");} catch(Exception e){throw new RuntimeException(e);} finally { if(path!=null) try{Files.deleteIfExists(path);}catch(Exception ignored){} }
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
            String sql="""
              with recursive l as (select id,base_version_id,generation_no,revision_no from td_bulk_ledger_versions where id=?
                union all select p.id,p.base_version_id,p.generation_no,p.revision_no from td_bulk_ledger_versions p join l on l.base_version_id=p.id where p.status='PUBLISHED' and p.generation_no=l.generation_no and p.revision_no=l.revision_no-1),
              ranked as (select tv.*,row_number() over(partition by tv.ticket_reference_hmac order by l.revision_no desc) rn from td_bulk_ledger_ticket_versions tv join l on l.id=tv.version_id)
              select r.ticket_reference_hmac,r.reservation_reference_hmac,r.account_reference_hmac,r.hmac_key_version,r.purchased_at,r.ticket_quantity,r.ticket_status,r.source_version,
                     s.payment_hmac,s.address_hmac,s.device_hmac,s.ip_hmac,s.hmac_key_version,s.source_version,s.hmac_purpose
              from ranked r left join td_bulk_ledger_track1_ticket_signals s on s.version_id=? and s.ticket_reference_hmac=r.ticket_reference_hmac
              where r.rn=1 order by r.ticket_reference_hmac
              """;
            jdbc.query(connection -> {
                var statement=connection.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                configureMySqlStreaming(statement);
                statement.setString(1,c.versionId().toString()); statement.setString(2,c.versionId().toString());
                return statement;
              },rs->{
                try {
                    String sourceVersion=rs.getString(8), ledgerKeyVersion=rs.getString(4), keyVersion=rs.getString(13);
                    // Snapshot validation has already inspected all states. Data receives only effective rows.
                    if (!"ACTIVE".equals(rs.getString(7))) return;
                    String invalid=inputFailure(rs.getString(7), ledgerKeyVersion, keyVersion, rs.getString(15), sourceVersion, rs.getString(14), rs.getString(9),rs.getString(10),rs.getString(11),rs.getString(12));
                    if(invalid!=null) throw new Blocked(invalid);
                    if(source[0]!=null&&!source[0].equals(sourceVersion)||key[0]!=null&&!key[0].equals(keyVersion)) throw new Blocked("LEDGER_SOURCE_OR_KEY_MIXED"); source[0]=sourceVersion; key[0]=keyVersion;
                    Map<String,Object> row=new LinkedHashMap<>(); row.put("accountId",rs.getString(3)); row.put("reservationNo",rs.getString(2)); row.put("eventId",m.externalEvent()); row.put("performanceId",c.performanceId().toString());
                    row.put("purchasedAt",rs.getTimestamp(5).toLocalDateTime().atOffset(ZoneOffset.UTC).toString()); row.put("effectiveTicketQuantity",rs.getInt(6)); row.put("paymentHash",rs.getString(9)); row.put("addressHash",rs.getString(10)); row.put("deviceIdHash",rs.getString(11)); row.put("ipHash",rs.getString(12));
                    row.put("quantityInputType","INDIVIDUAL_TICKET"); row.put("ticketReference",rs.getString(1)); row.put("ticketStatus",rs.getString(7)); row.put("sourceVersion",sourceVersion); row.put("sourceAsOf",m.sourceAsOf().atOffset(ZoneOffset.UTC).toString());
                    byte[] bytes=(json.writeValueAsString(row)+"\n").getBytes(StandardCharsets.UTF_8); w.write(new String(bytes,StandardCharsets.UTF_8)); digest.update(bytes); count[0]++; if(count[0]%PAGE==0) heartbeat(c);
                } catch(Blocked e){throw e;} catch(Exception e){throw new RuntimeException(e);}
              });
        }
        if(source[0]==null||key[0]==null) throw new Blocked("NO_EFFECTIVE_TICKETS");
        tx.executeWithoutResult(x->jdbc.update("update td_bulk_ledger_track1_bulk_jobs set source_version=?,hmac_key_version=? where id=? and fence_token=?",source[0],key[0],c.id().toString(),c.fence()));
        return count[0];
    }
    private void updateArtifact(Claim c,String artifact,BulkLedgerUploadStorage.VerifiedObject stored,Metadata m,long count) { require(c); jdbc.update("""
        update td_bulk_ledger_track1_bulk_jobs set artifact_id=?,object_key=?,object_version=?,artifact_sha256=?,record_count=?,source_as_of=?,policy_version=?,status='REGISTERING',lease_until=null,updated_at=utc_timestamp(6) where id=? and fence_token=?
        """,artifact,artifact,stored.versionId(),stored.sha256(),count,Timestamp.valueOf(m.sourceAsOf()),snapshot(c,null).snapshotId(),c.id().toString(),c.fence()); }
    /** Reads the fixed snapshot on retries; policy selection is never repeated once this row exists. */
    private Snapshot snapshot(Claim c,Metadata metadata) {
        List<Snapshot> existing=jdbc.query("select policy_verification_status,quantity_data_status,data_completeness_reason,cancellation_handling,duplicate_handling,max_tickets_per_account,policy_version,effective_from,effective_until,snapshot_id from td_bulk_ledger_track1_policy_snapshots where job_id=?",(rs,n)->snapshotRow(rs),c.id().toString());
        if(!existing.isEmpty()) return existing.getFirst();
        if(metadata==null) throw new Blocked("POLICY_SNAPSHOT_MISSING");
        List<PurchaseLimitPolicy> all=policies.findByTenantIdAndEventIdOrderByPerformanceIdAsc(c.tenantId(),c.eventId());
        SnapshotCandidate candidate=streamSnapshotCandidate(c,metadata,all);
        UUID snapshotId=UUID.randomUUID();
        tx.executeWithoutResult(x->{require(c);jdbc.update("insert into td_bulk_ledger_track1_policy_snapshots(job_id,snapshot_id,ledger_version_id,source_version,source_as_of,ledger_complete_snapshot,policy_verification_status,quantity_data_status,data_completeness_reason,cancellation_handling,duplicate_handling,policy_id,policy_revision,policy_version,max_tickets_per_account,effective_from,effective_until,created_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,utc_timestamp(6))",c.id().toString(),snapshotId.toString(),c.versionId().toString(),candidate.sourceVersion(),Timestamp.valueOf(metadata.sourceAsOf()),candidate.completeSnapshot(),candidate.policyStatus(),candidate.quantityStatus(),candidate.reason(),candidate.cancellationHandling(),"DEDUPLICATED",candidate.policy()==null?null:candidate.policy().getId().toString(),candidate.policy()==null?null:candidate.policy().getRevision(),candidate.policy()==null?null:candidate.policy().getPolicyVersion(),candidate.policy()==null?null:candidate.policy().getMaxTicketsPerAccount(),candidate.policy()==null?null:Timestamp.valueOf(candidate.policy().getEffectiveFrom()),candidate.policy()==null||candidate.policy().getEffectiveUntil()==null?null:Timestamp.valueOf(candidate.policy().getEffectiveUntil()));});
        return candidate.toSnapshot(snapshotId);
    }
    private SnapshotCandidate streamSnapshotCandidate(Claim c,Metadata m,List<PurchaseLimitPolicy> all) {
        final PurchaseLimitPolicy[] selected={null}; final String[] source={null}, reason={null}; final boolean[] complete={true}, policyValid={true};
        List<Boolean> sourceIsSnapshot=jdbc.query("select j.mode='FULL_SNAPSHOT' and j.complete_snapshot from td_bulk_ledger_versions v join td_bulk_import_jobs j on j.id=v.import_id where v.id=?",(rs,n)->rs.getBoolean(1),c.versionId().toString());
        if(sourceIsSnapshot.size()!=1||!sourceIsSnapshot.getFirst()) complete[0]=false;
        String sql="with recursive l as (select id,base_version_id,generation_no,revision_no from td_bulk_ledger_versions where id=? "
                + "union all select p.id,p.base_version_id,p.generation_no,p.revision_no from td_bulk_ledger_versions p join l on l.base_version_id=p.id "
                + "where p.status='PUBLISHED' and p.generation_no=l.generation_no and p.revision_no=l.revision_no-1), "
                + "ranked as (select tv.*,row_number() over(partition by tv.ticket_reference_hmac order by l.revision_no desc) rn "
                + "from td_bulk_ledger_ticket_versions tv join l on l.id=tv.version_id) "
                + "select ticket_reference_hmac,reservation_reference_hmac,account_reference_hmac,purchased_at,ticket_quantity,quantity_unit,ticket_status,source_version from ranked where rn=1 order by ticket_reference_hmac";
        jdbc.query(connection->{var statement=connection.prepareStatement(sql,ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY);configureMySqlStreaming(statement);statement.setString(1,c.versionId().toString());return statement;},rs->{try{String version=rs.getString(8);if(source[0]!=null&&!source[0].equals(version)){complete[0]=false;reason[0]="PUBLIC_LEDGER_SOURCE_VERSION_MIXED";}source[0]=version;if(rs.getTimestamp(4)==null||rs.getTimestamp(4).toLocalDateTime().isAfter(m.sourceAsOf())){complete[0]=false;reason[0]="PUBLIC_LEDGER_SOURCE_AS_OF_INVALID";}PurchaseLimitPolicySelector.Result choice=PurchaseLimitPolicySelector.select(all,new PurchaseLimitPolicySelector.Context(c.performanceId(),null,PurchaseLimitPolicy.LimitSubject.ACCOUNT,false,rs.getTimestamp(4)==null?null:rs.getTimestamp(4).toLocalDateTime()));if(choice.status()!=PurchaseLimitPolicySelector.Status.SELECTED||choice.policy().getApplicationScope()==PurchaseLimitPolicy.ApplicationScope.EVENT||(selected[0]!=null&&!selected[0].getId().equals(choice.policy().getId()))){policyValid[0]=false;reason[0]="NO_CONFIRMED_ACCOUNT_POLICY";return;}if(policyValid[0])selected[0]=choice.policy();PurchaseLimitQuantityInputContract.Assessment assessment=PurchaseLimitQuantityInputContract.assess(choice.policy(),new PurchaseLimitQuantityInputContract.IndividualTicket(rs.getString(3),rs.getString(2),rs.getString(1),rs.getTimestamp(4).toInstant(),rs.getInt(5),PurchaseLimitQuantityInputContract.TicketStatus.valueOf(rs.getString(7)),version,m.sourceAsOf().toInstant(ZoneOffset.UTC)));if(assessment.status()!=PurchaseLimitQuantityInputContract.AssessmentStatus.READY_FOR_INDIVIDUAL_TICKET_V2){complete[0]=false;reason[0]="PUBLIC_LEDGER_QUANTITY_INCOMPLETE";}}catch(Exception e){throw new RuntimeException(e);}});
        if(!policyValid[0]||selected[0]==null)return new SnapshotCandidate(null,"POLICY_NOT_CONFIRMED","INCOMPLETE",source[0],false,"NOT_CONFIRMED",reason[0]==null?"NO_CONFIRMED_ACCOUNT_POLICY":reason[0]);
        if(selected[0].getCancellationRefundRule()!=PurchaseLimitPolicy.CancellationRefundRule.NETTED)return new SnapshotCandidate(selected[0],"CONFIRMED","INCOMPLETE",source[0],false,"GROSS", "GROSS_RULE_NOT_SUPPORTED");
        return new SnapshotCandidate(selected[0],"CONFIRMED",complete[0]?"COMPLETE":"INCOMPLETE",source[0],complete[0],"NETTED",complete[0]?null:(reason[0]==null?"PUBLIC_LEDGER_SNAPSHOT_INCOMPLETE":reason[0]));
    }
    private Snapshot snapshotRow(ResultSet rs)throws java.sql.SQLException{String status=rs.getString(1);return new Snapshot(rs.getString(10),status,rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),"CONFIRMED".equals(status)?new Track1BulkJobDto.ConfirmedPolicy(rs.getInt(6),rs.getString(7),rs.getTimestamp(8).toLocalDateTime().atOffset(ZoneOffset.UTC),rs.getTimestamp(9)==null?null:rs.getTimestamp(9).toLocalDateTime().atOffset(ZoneOffset.UTC)):null);}
    private void register(Claim c) {
        Job j=job(c); Track1BulkJobDto.Manifest manifest=new Track1BulkJobDto.Manifest(j.artifactId(),j.objectKey(),j.objectVersion(),j.sha256(),j.recordCount(),"track1-limit-ndjson-v1",c.tenantId().toString(),j.externalEvent(),c.versionId().toString(),Long.toString(j.generation()),Long.toString(j.revision()),j.sourceVersion(),j.sourceAsOf().atOffset(ZoneOffset.UTC),"TRACK1_CONNECTION",j.keyVersion(),snapshot(c,null).snapshotId(),List.of(scope(c,j)));
        Track1BulkJobDto.Accepted accepted=data.createBulkJob(new Track1BulkJobDto.Create(manifest),CandidatePipelineSupport.sha256("BL07",c.outboxId().toString(),j.sha256()));
        tx.executeWithoutResult(x->{require(c);jdbc.update("update td_bulk_ledger_track1_bulk_jobs set data_job_id=?,data_status=?,status='QUEUED',lease_until=null,updated_at=utc_timestamp(6) where id=? and fence_token=?",accepted.jobId().toString(),accepted.status(),c.id().toString(),c.fence());});
    }
    private Track1BulkJobDto.Scope scope(Claim c,Job j) { Snapshot s=snapshot(c,null); return scopeForData(c.eventId(),j.externalEvent(),c.performanceId(),j.sourceVersion(),j.sourceAsOf().atOffset(ZoneOffset.UTC),s.policyStatus(),s.confirmedPolicy(),s.quantityStatus(),s.cancellationHandling(),s.reason()); }
    /** Data does not support GROSS calculations; the durable snapshot retains it but the manifest never claims it. */
    static Track1BulkJobDto.Scope scopeForData(UUID internalEventId,String eventId,UUID performanceId,String sourceVersion,java.time.OffsetDateTime sourceAsOf,String policyStatus,Track1BulkJobDto.ConfirmedPolicy policy,String quantityStatus,String cancellationHandling,String reason) {
        boolean gross="GROSS".equals(cancellationHandling);
        return new Track1BulkJobDto.Scope(internalEventId,eventId,performanceId,gross?"POLICY_NOT_CONFIRMED":policyStatus,gross?null:policy,gross?"INCOMPLETE":quantityStatus,sourceVersion,sourceAsOf,gross?"NOT_CONFIRMED":cancellationHandling,"DEDUPLICATED",gross?"GROSS_RULE_NOT_SUPPORTED":reason);
    }
    private void pollOrCollect(Claim c) {
        if(c.dataJobId()==null) throw new Blocked("DATA_JOB_MISSING"); Track1BulkJobDto.Status status=data.bulkJobStatus(c.dataJobId());
        if("COMPLETED".equals(status.status())) { collect(c,status); return; }
        if(Set.of("FAILED","INPUT_REJECTED","CANCELLED","INTERRUPTED").contains(status.status())) { dataFailure(c,status.failureCode()==null?"DATA_"+status.status():status.failureCode()); return; }
        tx.executeWithoutResult(x->{require(c);jdbc.update("update td_bulk_ledger_track1_bulk_jobs set data_status=?,status=?,lease_until=null,updated_at=utc_timestamp(6) where id=? and fence_token=?",status.status(),"RUNNING".equals(status.status())?"RUNNING":"QUEUED",c.id().toString(),c.fence());});
    }
    private void collect(Claim c,Track1BulkJobDto.Status status) {
        Job j=job(c);
        if (j.collectionPhase()==null || "CLUSTERS".equals(j.collectionPhase())) { collectClusters(c,j); return; }
        if ("SHARED_SIGNALS".equals(j.collectionPhase())) { collectShared(c,j); return; }
        if ("EVALUATIONS".equals(j.collectionPhase())) { collectEvaluations(c,j); return; }
        if ("FINDINGS".equals(j.collectionPhase())) { collectFindings(c,j); return; }
        throw new Blocked("DATA_COLLECTION_PHASE_INVALID");
    }
    private void collectClusters(Claim c,Job j) {
        Track1BulkJobDto.Results page=data.bulkResults(c.dataJobId(),j.cursor());
        if(!c.dataJobId().equals(page.jobId())||!"COMPLETED".equals(page.status())||!"NOT_IMPLEMENTED".equals(page.sharedSignalObservationsStatus())||!"NOT_IMPLEMENTED".equals(page.purchaseLimitEvaluationsStatus())||page.clusters()==null) throw new Blocked("DATA_RESULT_CONTRACT_INVALID");
        for(Track1BulkJobDto.Cluster cluster:page.clusters()) {
            tx.executeWithoutResult(x->{require(c);saveCluster(c,j.externalEvent(),cluster);heartbeat(c);});
            if(cluster.hasMoreMembers()) { String cursor=null; do {
                // Data I/O stays outside the short transaction that persists this one cursor page.
                Track1BulkJobDto.Members members=data.bulkMembers(c.dataJobId(),cluster.clusterId(),cursor);
                tx.executeWithoutResult(x->{require(c);members.members().forEach(m->saveMember(c,j.externalEvent(),cluster.clusterId(),m));heartbeat(c);});
                cursor=members.nextCursor();
            } while(cursor!=null); }
        }
        tx.executeWithoutResult(x->{require(c);String next=page.nextCursor(); rejectCursor(j.cursor(),next); jdbc.update("update td_bulk_ledger_track1_bulk_jobs set data_status='COMPLETED',algorithm_version=?,result_cursor=?,collection_phase=?,status='COLLECTING',lease_until=null,updated_at=utc_timestamp(6) where id=? and fence_token=?",page.algorithmVersion(),next,next==null?"SHARED_SIGNALS":"CLUSTERS",c.id().toString(),c.fence());});
    }
    private void collectShared(Claim c,Job j) {
        var page=data.bulkSharedSignalObservations(c.dataJobId(),j.sharedCursor()); Set<String> ids=new HashSet<>();
        for(var item:page.sharedSignalObservations()) { if(!c.performanceId().toString().equals(item.scopeId())||!ids.add(item.observationId()))throw new Blocked("DATA_SHARED_SIGNAL_SCOPE_INVALID"); tx.executeWithoutResult(x->{require(c);saveShared(c,item);heartbeat(c);}); }
        tx.executeWithoutResult(x->{require(c);String next=page.nextCursor();rejectCursor(j.sharedCursor(),next);jdbc.update("update td_bulk_ledger_track1_bulk_jobs set shared_signal_observations_status='COMPLETED',shared_signal_cursor=?,collection_phase=?,lease_until=null,updated_at=utc_timestamp(6) where id=? and fence_token=?",next,next==null?"EVALUATIONS":"SHARED_SIGNALS",c.id().toString(),c.fence());});
    }
    private void collectEvaluations(Claim c,Job j) {
        var page=data.bulkPurchaseLimitEvaluations(c.dataJobId(),j.evaluationCursor()); Set<UUID> performances=new HashSet<>();
        for(var item:page.purchaseLimitEvaluations()) { if(!c.eventId().equals(item.internalEventId())||!j.externalEvent().equals(item.eventId())||!c.performanceId().equals(item.performanceId())||!performances.add(item.performanceId()))throw new Blocked("DATA_EVALUATION_SCOPE_INVALID");tx.executeWithoutResult(x->{require(c);saveEvaluation(c,item,j.evaluationCursor());heartbeat(c);}); }
        tx.executeWithoutResult(x->{require(c);String next=page.nextCursor();rejectCursor(j.evaluationCursor(),next);jdbc.update("update td_bulk_ledger_track1_bulk_jobs set purchase_limit_evaluations_status='COMPLETED',purchase_limit_evaluation_cursor=?,collection_phase=?,lease_until=null,updated_at=utc_timestamp(6) where id=? and fence_token=?",next,next==null?"FINDINGS":"EVALUATIONS",c.id().toString(),c.fence());});
    }
    private void collectFindings(Claim c,Job j) {
        List<FindingCheckpoint> rows=jdbc.query("select performance_id,findings_cursor,expected_finding_count,policy_version from td_bulk_ledger_track1_bulk_limit_evaluations where job_id=? and evaluation_status='EVALUATED' and findings_status<>'COMPLETED' order by performance_id limit 1",(rs,n)->new FindingCheckpoint(uuid(rs.getString(1)),rs.getString(2),rs.getInt(3),rs.getString(4)),c.id().toString());
        if(rows.isEmpty()){tx.executeWithoutResult(x->{require(c);verifyFindings(c);jdbc.update("update td_bulk_ledger_track1_bulk_jobs set collection_phase='COMPLETED',status='COMPLETED',completed_at=utc_timestamp(6),lease_until=null,updated_at=utc_timestamp(6) where id=? and fence_token=?",c.id().toString(),c.fence());});return;}
        FindingCheckpoint checkpoint=rows.getFirst();var page=data.bulkPurchaseLimitFindings(c.dataJobId(),checkpoint.performanceId(),checkpoint.cursor());if(!checkpoint.policyVersion().equals(page.policyVersion()))throw new Blocked("DATA_FINDING_POLICY_VERSION_MISMATCH");Set<String> accounts=new HashSet<>();for(var item:page.accountLimitFindings()){if(!accounts.add(item.accountId()))throw new Blocked("DATA_FINDING_DUPLICATE");tx.executeWithoutResult(x->{require(c);saveFinding(c,checkpoint.performanceId(),item);heartbeat(c);});}
        tx.executeWithoutResult(x->{require(c);String next=page.nextCursor();rejectCursor(checkpoint.cursor(),next);jdbc.update("update td_bulk_ledger_track1_bulk_limit_evaluations set findings_cursor=?,findings_status=? where job_id=? and performance_id=?",next,next==null?"COMPLETED":"COLLECTING",c.id().toString(),checkpoint.performanceId().toString());});
    }
    private void saveShared(Claim c,Track1BulkJobDto.SharedSignalObservation item){jdbc.update("insert into td_bulk_ledger_track1_bulk_shared_signal_observations(job_id,observation_id,performance_id,scope_type,signal_type,distinct_account_count,scope_account_count,distinct_reservation_count,total_ticket_quantity,ticket_quantity_status,payment_shared_account_count,device_shared_account_count,ip_network_type,purchase_window_seconds,excluded_from_louvain) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) on duplicate key update observation_id=values(observation_id)",c.id().toString(),item.observationId(),c.performanceId().toString(),item.scopeType(),item.signalType(),item.distinctAccountCount(),item.scopeAccountCount(),item.distinctReservationCount(),item.totalTicketQuantity(),item.ticketQuantityStatus(),item.corroboration().paymentSharedAccountCount(),item.corroboration().deviceSharedAccountCount(),item.ipNetworkType(),item.purchaseWindowSeconds(),item.excludedFromLouvain());}
    private void saveEvaluation(Claim c,Track1BulkJobDto.PurchaseLimitEvaluation item,String sourceCursor){List<String> existing=jdbc.query("select source_cursor from td_bulk_ledger_track1_bulk_limit_evaluations where job_id=? and performance_id=?",(rs,n)->rs.getString(1),c.id().toString(),item.performanceId().toString());if(!existing.isEmpty()&&!sameEvaluationCursor(existing.getFirst(),sourceCursor))throw new Blocked("DATA_EVALUATION_DUPLICATE");jdbc.update("insert into td_bulk_ledger_track1_bulk_limit_evaluations(job_id,performance_id,internal_event_id,external_event_id,evaluation_status,policy_version,expected_finding_count,source_cursor,findings_status) values(?,?,?,?,?,?,?,?,?) on duplicate key update evaluation_status=values(evaluation_status),policy_version=values(policy_version),expected_finding_count=values(expected_finding_count)",c.id().toString(),item.performanceId().toString(),item.internalEventId().toString(),item.eventId(),item.status(),item.policyVersion(),item.accountLimitFindingCount(),sourceCursor,"EVALUATED".equals(item.status())?"PENDING":"NOT_APPLICABLE");}
    private void saveFinding(Claim c,UUID performance,Track1BulkJobDto.AccountLimitFinding item){jdbc.update("insert into td_bulk_ledger_track1_bulk_limit_findings(job_id,performance_id,account_reference_hmac,effective_ticket_quantity,max_tickets_per_account,policy_version) values(?,?,?,?,?,?) on duplicate key update effective_ticket_quantity=values(effective_ticket_quantity),max_tickets_per_account=values(max_tickets_per_account),policy_version=values(policy_version)",c.id().toString(),performance.toString(),item.accountId(),item.effectiveTicketQuantity(),item.maxTicketsPerAccount(),item.policyVersion());for(var reservation:item.reservationQuantities())jdbc.update("insert into td_bulk_ledger_track1_bulk_limit_finding_reservations(job_id,performance_id,account_reference_hmac,reservation_reference_hmac,effective_ticket_quantity) values(?,?,?,?,?) on duplicate key update effective_ticket_quantity=values(effective_ticket_quantity)",c.id().toString(),performance.toString(),item.accountId(),reservation.reservationNo(),reservation.effectiveTicketQuantity());}
    private void verifyFindings(Claim c){Integer required=jdbc.queryForObject("select count(*) from td_bulk_ledger_track1_bulk_limit_evaluations where job_id=? and performance_id=?",Integer.class,c.id().toString(),c.performanceId().toString());Integer total=jdbc.queryForObject("select count(*) from td_bulk_ledger_track1_bulk_limit_evaluations where job_id=?",Integer.class,c.id().toString());if(required==null||total==null||!exactlyOneRequiredEvaluation(required,total))throw new Blocked(required==null||required!=1?"DATA_EVALUATION_MISSING":"DATA_EVALUATION_SCOPE_INVALID");Integer n=jdbc.queryForObject("select count(*) from td_bulk_ledger_track1_bulk_limit_evaluations e where e.job_id=? and (e.findings_status not in ('COMPLETED','NOT_APPLICABLE') or e.expected_finding_count<>(select count(*) from td_bulk_ledger_track1_bulk_limit_findings f where f.job_id=e.job_id and f.performance_id=e.performance_id))",Integer.class,c.id().toString());if(n==null||n!=0)throw new Blocked("DATA_FINDING_COUNT_MISMATCH");}
    static boolean sameEvaluationCursor(String stored,String current){return Objects.equals(stored,current);}
    static boolean exactlyOneRequiredEvaluation(int required,int total){return required==1&&total==1;}
    static void rejectCursor(String previous,String next){if(next!=null&&(!next.matches("\\d+")||next.equals(previous)||(previous!=null&&Long.parseLong(next)<=Long.parseLong(previous))))throw new Blocked("DATA_CURSOR_INVALID");}
    private void saveCluster(Claim c,String externalEvent,Track1BulkJobDto.Cluster cluster) { jdbc.update("insert into td_bulk_ledger_track1_bulk_clusters(job_id,cluster_id,risk_score,member_count,payment_hashes_json,address_hashes_json,device_hashes_json,ip_hashes_json) values(?,?,?,?,cast(? as json),cast(? as json),cast(? as json),cast(? as json)) on duplicate key update risk_score=values(risk_score),member_count=values(member_count)",c.id().toString(),cluster.clusterId(),cluster.riskScore(),cluster.memberCount(),asJson(cluster.paymentHashes()),asJson(cluster.addressHashes()),asJson(cluster.deviceIdHashes()),asJson(cluster.ipHashes())); cluster.members().forEach(m->saveMember(c,externalEvent,cluster.clusterId(),m)); }
    private void saveMember(Claim c,String externalEvent,String cluster,Track1BulkJobDto.Member member){ if(!externalEvent.equals(member.eventId())||!member.accountId().matches("[0-9a-f]{64}")||!member.reservationNo().matches("[0-9a-f]{64}")) throw new Blocked("DATA_MEMBER_SCOPE_INVALID"); jdbc.update("insert ignore into td_bulk_ledger_track1_bulk_members(job_id,cluster_id,account_reference_hmac,reservation_reference_hmac) values(?,?,?,?)",c.id().toString(),cluster,member.accountId(),member.reservationNo()); }
    private void retryData(Claim c){
        if(c.dataJobId()==null) { Job j=job(c); resumeWithoutDataJob(c,j.artifactId()==null?"PREPARING":"REGISTERING","DATA_JOB_MISSING"); return; }
        Track1BulkJobDto.Accepted a=data.retryBulkJob(c.dataJobId()); tx.executeWithoutResult(x->{require(c);jdbc.update("update td_bulk_ledger_track1_bulk_jobs set status='QUEUED',data_status=?,failure_code=null,lease_until=null,updated_at=utc_timestamp(6) where id=? and fence_token=?",a.status(),c.id().toString(),c.fence());});
    }
    private void transientFailure(Claim c,String code){ retryAt(c,recoveryStage(c.status(),c.dataJobId()!=null),code); }
    private void dataFailure(Claim c,String code){ retryAt(c,"RETRYABLE",code); }
    private void resumeWithoutDataJob(Claim c,String stage,String code){tx.executeWithoutResult(x->{require(c);jdbc.update("update td_bulk_ledger_track1_bulk_jobs set status=?,failure_code=?,lease_until=null,updated_at=utc_timestamp(6) where id=? and fence_token=?",stage,code,c.id().toString(),c.fence());});}
    private void retryAt(Claim c,String stage,String code){tx.executeWithoutResult(x->{require(c); Integer attempts=jdbc.queryForObject("select attempt_count from td_bulk_ledger_track1_bulk_jobs where id=?",Integer.class,c.id().toString()); int before=attempts==null?0:attempts; jdbc.update("update td_bulk_ledger_track1_bulk_jobs set status=?,failure_code=?,attempt_count=?,lease_until=null,updated_at=utc_timestamp(6) where id=? and fence_token=?",failureState(before,stage),code,before+1,c.id().toString(),c.fence());});}
    private void fail(Claim c,String code,boolean retryable){ if(retryable){transientFailure(c,code);return;} tx.executeWithoutResult(x->{require(c);jdbc.update("update td_bulk_ledger_track1_bulk_jobs set status='BLOCKED',failure_code=?,lease_until=null,updated_at=utc_timestamp(6) where id=? and fence_token=?",code,c.id().toString(),c.fence());}); }
    private Job job(Claim c){ return jdbc.queryForObject("select j.artifact_id,j.object_key,j.object_version,j.artifact_sha256,j.record_count,j.source_version,j.source_as_of,j.hmac_key_version,j.policy_version,v.generation_no,v.revision_no,e.external_event_ref,j.result_cursor,j.collection_phase,j.shared_signal_cursor,j.purchase_limit_evaluation_cursor from td_bulk_ledger_track1_bulk_jobs j join td_bulk_ledger_versions v on v.id=j.ledger_version_id join events e on e.id=j.event_id where j.id=?",(rs,n)->new Job(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getLong(5),rs.getString(6),local(rs.getTimestamp(7)),rs.getString(8),rs.getString(9),rs.getLong(10),rs.getLong(11),rs.getString(12),rs.getString(13),rs.getString(14),rs.getString(15),rs.getString(16)),c.id().toString()); }
    private void require(Claim c){Integer ok=jdbc.queryForObject("select count(*) from td_bulk_ledger_track1_bulk_jobs where id=? and fence_token=? and lease_owner=?",Integer.class,c.id().toString(),c.fence(),owner(c));if(ok==null||ok!=1)throw new Blocked("WORKER_FENCE_LOST");}
    private void heartbeat(Claim c){if(!fenceUpdateAccepted(jdbc.update("update td_bulk_ledger_track1_bulk_jobs set lease_until=date_add(utc_timestamp(6),interval 30 minute),updated_at=utc_timestamp(6) where id=? and fence_token=? and lease_owner=?",c.id().toString(),c.fence(),owner(c))))throw new Blocked("WORKER_FENCE_LOST");}
    static boolean fenceUpdateAccepted(int updatedRows) { return updatedRows == 1; }
    static void configureMySqlStreaming(PreparedStatement statement) throws java.sql.SQLException { statement.setFetchSize(MYSQL_STREAMING_FETCH_SIZE); }
    static String recoveryStage(String currentStatus, boolean hasDataJob) {
        if ("PREPARING".equals(currentStatus) || "REGISTERING".equals(currentStatus)) return currentStatus;
        return hasDataJob ? currentStatus : "PREPARING";
    }
    static String failureState(int failuresBefore, String retryStage) { return failuresBefore >= 2 ? "FAILED" : retryStage; }
    static boolean manifestObjectVersionCompatible(String value) { return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"); }
    static String inputFailure(String ticketStatus,String ledgerKeyVersion,String signalKeyVersion,String purpose,String ticketSourceVersion,String signalSourceVersion,String payment,String address,String device,String ip) {
        if(!"ACTIVE".equals(ticketStatus)) return "LEDGER_TICKET_STATUS_NOT_SUPPORTED";
        if(signalKeyVersion==null||payment==null&&address==null&&device==null&&ip==null) return "LEDGER_SIGNAL_MISSING";
        if(ledgerKeyVersion==null||!ledgerKeyVersion.equals(signalKeyVersion)) return "LEDGER_HMAC_KEY_VERSION_MISMATCH";
        if(!"TRACK1_CONNECTION".equals(purpose)||ticketSourceVersion==null||!ticketSourceVersion.equals(signalSourceVersion)) return "LEDGER_SIGNAL_OR_PROVENANCE_INVALID";
        return null;
    }
    private static String owner(Claim c){return "bl07-"+c.id();} private static UUID uuid(String s){return UUID.fromString(s);} private static UUID nullable(String s){return s==null?null:uuid(s);} private static LocalDateTime local(Timestamp t){return t==null?null:t.toLocalDateTime();} private String asJson(Object v){try{return json.writeValueAsString(v);}catch(Exception e){throw new IllegalStateException(e);}}
    private record Claim(UUID id,UUID outboxId,UUID tenantId,UUID eventId,UUID performanceId,UUID versionId,String status,UUID dataJobId,long fence){} private record Metadata(LocalDateTime sourceAsOf,long generation,long revision,int lineageCount,String externalEvent,String ignored){} private record Job(String artifactId,String objectKey,String objectVersion,String sha256,long recordCount,String sourceVersion,LocalDateTime sourceAsOf,String keyVersion,String policyVersion,long generation,long revision,String externalEvent,String cursor,String collectionPhase,String sharedCursor,String evaluationCursor){} private record FindingCheckpoint(UUID performanceId,String cursor,int expectedCount,String policyVersion){} private record Snapshot(String snapshotId,String policyStatus,String quantityStatus,String reason,String cancellationHandling,String duplicateHandling,Track1BulkJobDto.ConfirmedPolicy confirmedPolicy){} private record SnapshotCandidate(PurchaseLimitPolicy policy,String policyStatus,String quantityStatus,String sourceVersion,boolean completeSnapshot,String cancellationHandling,String reason){Snapshot toSnapshot(UUID snapshotId){return new Snapshot(snapshotId.toString(),policyStatus,quantityStatus,reason,cancellationHandling,"DEDUPLICATED",policy==null?null:new Track1BulkJobDto.ConfirmedPolicy(policy.getMaxTicketsPerAccount(),policy.getPolicyVersion(),policy.getEffectiveFrom().atOffset(ZoneOffset.UTC),policy.getEffectiveUntil()==null?null:policy.getEffectiveUntil().atOffset(ZoneOffset.UTC)));}} private static final class Blocked extends RuntimeException{final String code;Blocked(String c){code=c;}}
}
