package com.ticket.defender_core.application.service;

import com.ticket.defender_core.security.AdminRole;
import com.ticket.defender_core.adapter.out.api.FastApiAdapter;
import com.ticket.defender_core.adapter.out.api.FastApiResponseContractException;
import com.ticket.defender_core.adapter.out.api.dto.MultiEventAnalysisDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Spring 소유의 다공연 실행 범위와 조회 모델입니다. 원장 head는 생성 시점에 고정한다. */
@Service
public class MultiEventInvestigationService {
    static final Set<AdminRole> ROLES=Set.of(AdminRole.TENANT_ADMIN,AdminRole.ANALYST,AdminRole.REVIEWER,AdminRole.APPROVER);
    private static final int MAX_JSON_TICKETS=50_000;
    private static final int MAX_ARTIFACT_TICKETS=400_000;
    private static final long DEFAULT_MAX_ARTIFACT_BYTES=1_073_741_824L;
    private static final long MAX_IMPORTED_MEMBER_ROWS=5_000_000L;
    private static final long MAX_IMPORTED_RELATIONSHIP_MEMBER_ROWS=5_000_000L;
    private static final int MYSQL_STREAMING_FETCH_SIZE=Integer.MIN_VALUE;
    private static final int RESULT_PAGE_SIZE=100;
    private static final int MEMBER_PAGE_SIZE=500;
    private static final String FULL_SCOPE_AUTHORIZATION = """
        not exists (
          select 1 from td_multi_event_investigation_scopes related
          left join events related_event on related_event.id=related.event_id and related_event.tenant_id=related.tenant_id
          where related.run_id=r.id and (
            related.tenant_id<>r.tenant_id or related_event.id is null or not exists (
              select 1 from membership_events current_grant
              where current_grant.tenant_id=? and current_grant.membership_id=? and current_grant.event_id=related.event_id
            )
          )
        )
        """;
    /** 회차 수로 R을 계산하는 다회차 후보 모델입니다. */
    public static final String SCORE_VERSION="score-v2-exp2-multi-performance-recurrence";
    /** event 수로 R을 계산하던 퇴역 모델입니다. 저장된 실행은 읽기만 하고 다시 실행하지 않습니다. */
    public static final String RETIRED_SCORE_VERSION="score-v2-exp2-multi-event-recurrence";
    static final String COCLUSTER="stable_account_cocluster";
    static final Set<String> STRONG_SIGNALS=Set.of("refund_account_hash","phone_hash","device_id_hash","address_hash");
    static final Set<String> RELATIONSHIP_TYPES=Set.of("refund_account_hash","phone_hash","device_id_hash","address_hash",COCLUSTER);
    static final Set<String> IP_NETWORK_TYPES=Set.of("RESIDENTIAL","CORPORATE_EDU","DATACENTER_PROXY","UNKNOWN","MOBILE_CGNAT");
    static final Set<String> EXP2_SIGNALS=Set.of("refund_account_hash","phone_hash","device_id_hash","address_hash","payment_hash",
            "ip_window:RESIDENTIAL","ip_window:CORPORATE_EDU","ip_window:DATACENTER_PROXY","ip_window:UNKNOWN","ip_window:MOBILE_CGNAT");
    static final Set<String> UNAVAILABLE_SIGNALS=Set.of("refund_account_hash","phone_hash","device_id_hash","address_hash","payment_hash","ip_window");
    static final int IP_WINDOW_SECONDS=600;
    /**
     * Data의 {@code ip_window_token}과 같은 600초 구간입니다. Data는 UTC 구매 시각의 정수 epoch 초를 600으로 나눈 몫을 쓰며,
     * purchased_at은 UTC DATETIME이므로 세션 시간대와 무관한 TIMESTAMPDIFF로 같은 값을 만듭니다.
     */
    public static final String IP_WINDOW_BUCKET_SQL="(timestampdiff(second,'1970-01-01 00:00:00',%s) div "+IP_WINDOW_SECONDS+")";

    /** {@link #IP_WINDOW_BUCKET_SQL}과 같은 구간을 Java에서 계산합니다. 경계 시각 벡터 테스트의 기준입니다. */
    static long ipWindowBucket(java.time.LocalDateTime utcPurchasedAt) {
        return Math.floorDiv(utcPurchasedAt.toEpochSecond(ZoneOffset.UTC), IP_WINDOW_SECONDS);
    }

    private final JdbcTemplate jdbc; private final TransactionTemplate tx; private final CandidatePipelineAccess access; private final FastApiAdapter data;
    private final BulkLedgerUploadStorage storage;
    private final boolean enabled; private final boolean cancelledTicketTransferEnabled;
    private final Set<String> approvedSupplierNamespaces; private final ObjectMapper json=JsonMapper.builder().build();
    @Value("${ticket-defender.bulk-ledger-upload.max-bytes:1073741824}") private long maxArtifactBytes=DEFAULT_MAX_ARTIFACT_BYTES;
    @Autowired
    public MultiEventInvestigationService(JdbcTemplate jdbc,TransactionTemplate tx,CandidatePipelineAccess access,FastApiAdapter data,
            BulkLedgerUploadStorage storage,
            @Value("${ticket-defender.multi-event-investigation.enabled:false}") boolean enabled,
            @Value("${ticket-defender.multi-event-investigation.approved-supplier-namespaces:}") String approvedSupplierNamespaces,
            @Value("${ticket-defender.bulk-ledger-upload.cancelled-ticket-transfer.enabled:false}") boolean cancelledTicketTransferEnabled){
        this.jdbc=jdbc;this.tx=tx;this.access=access;this.data=data;this.storage=storage;this.enabled=enabled;
        this.cancelledTicketTransferEnabled=cancelledTicketTransferEnabled;
        this.approvedSupplierNamespaces=approvedSupplierNamespaces==null?Set.of():java.util.Arrays.stream(approvedSupplierNamespaces.split(","))
                .map(String::trim).filter(value->!value.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    MultiEventInvestigationService(JdbcTemplate jdbc,TransactionTemplate tx,CandidatePipelineAccess access,FastApiAdapter data,
            boolean enabled,String approvedSupplierNamespaces){
        this(jdbc,tx,access,data,null,enabled,approvedSupplierNamespaces,false);
    }

    MultiEventInvestigationService(JdbcTemplate jdbc,TransactionTemplate tx,CandidatePipelineAccess access,FastApiAdapter data,
            boolean enabled,String approvedSupplierNamespaces,boolean cancelledTicketTransferEnabled){
        this(jdbc,tx,access,data,null,enabled,approvedSupplierNamespaces,cancelledTicketTransferEnabled);
    }

    @Scheduled(fixedDelayString="${ticket-defender.multi-event-investigation.worker-delay:PT30S}") public void scheduled(){if(enabled){expireExhaustedLeases();consumeOne();}}
    public void consumeOne(){
        if(!enabled)return;
        Claim claim=tx.execute(x->claim());
        if(claim==null)return;
        try {
            if(!SCORE_VERSION.equals(runInput(claim).scoreVersion())) throw new Blocked("SCORE_VERSION_RETIRED");
            if(claim.dataJobId()==null){
                if(claim.artifactId()==null) prepareArtifact(claim);
                else registerArtifact(claim);
            } else pollAndCollect(claim);
        } catch(FastApiResponseContractException e){finish(claim,"BLOCKED","DATA_CONTRACT_INVALID");}
        catch(BulkLedgerUploadStorage.InvalidObject|BulkLedgerUploadStorage.ObjectChanged e){finish(claim,"BLOCKED","MULTI_EVENT_ARTIFACT_INVALID");}
        catch(BulkLedgerUploadStorage.Unavailable|org.springframework.web.client.ResourceAccessException|org.springframework.web.client.HttpServerErrorException e){finish(claim,"RETRYABLE","DATA_UNAVAILABLE");}
        catch(HttpClientErrorException e){if(isDataQueueFull(e))markQueueFullPending(claim);else finish(claim,"BLOCKED","DATA_CONTRACT_INVALID");}
        catch(Blocked e){finish(claim,"BLOCKED",e.code);}
        catch(RuntimeException e){finish(claim,"FAILED","MULTI_EVENT_ANALYSIS_FAILED");}
    }

    boolean isDataQueueFull(HttpClientErrorException error){
        if(error.getStatusCode().value()!=429)return false;
        try {
            var detail=json.readTree(error.getResponseBodyAsByteArray()).path("detail");
            var code=detail.path("code");
            return code.isString()&&"MULTI_EVENT_JOB_QUEUE_FULL".equals(code.stringValue());
        } catch(RuntimeException invalidErrorBody){return false;}
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Accepted create(String idempotencyKey, CreateRequest input) {
        if (!enabled) throw incomplete("MULTI_EVENT_DISABLED");
        if (idempotencyKey==null||idempotencyKey.isBlank()||idempotencyKey.length()>128||input==null||input.eventIds()==null||input.eventIds().isEmpty()||input.eventIds().size()>50
                ||input.analysisStartAt()==null||input.analysisEndAt()==null||!input.analysisEndAt().isAfter(input.analysisStartAt())||input.analysisEndAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC)))
            throw invalid();
        if(input.eventIds().stream().anyMatch(java.util.Objects::isNull)) throw invalid();
        var ids=input.eventIds().stream().distinct().sorted().toList(); if(ids.isEmpty()) throw invalid();
        if(input.performanceSelections()!=null&&input.performanceSelections().stream().anyMatch(selection->selection==null||selection.eventId()==null||selection.performanceIds()==null||selection.performanceIds().stream().anyMatch(java.util.Objects::isNull)||selection.performanceIds().size()!=Set.copyOf(selection.performanceIds()).size())) throw invalid();
        Map<UUID,Set<UUID>> selectionMap=input.performanceSelections()==null?Map.of():input.performanceSelections().stream()
                .collect(Collectors.toMap(PerformanceSelection::eventId,selection->Set.copyOf(selection.performanceIds()),(left,right)->{throw invalid();}));
        if(!ids.containsAll(selectionMap.keySet())||selectionMap.values().stream().mapToInt(Set::size).sum()>500) throw invalid();
        CandidatePipelineAccess.Scope first=access.event(ids.getFirst(),ROLES);
        for(UUID event:ids){var scope=access.event(event,ROLES);if(!first.tenantId().equals(scope.tenantId())||!first.membershipId().equals(scope.membershipId()))throw notFound();}
        List<Scope> scopes=new ArrayList<>();
        for(UUID event:ids){
            Set<UUID> selected=selectionMap.getOrDefault(event,Set.of());
            List<Scope> chosen=heads(first.tenantId(),event,selected);
            if(!selected.isEmpty()&&chosen.size()!=selected.size()) throw notFound();
            if(chosen.isEmpty()) throw incomplete("MULTI_EVENT_PUBLISHED_SCOPE_MISSING");
            scopes.addAll(chosen);
            if(scopes.size()>500) throw incomplete("MULTI_EVENT_SCOPE_LIMIT_EXCEEDED");
        }
        // 같은 공연의 다른 회차도 반복 근거이므로 event 수가 아니라 서로 다른 회차 수를 확인합니다.
        if(scopes.stream().map(Scope::performanceId).distinct().count()<2) throw incomplete("MULTI_EVENT_PERFORMANCE_SCOPE_REQUIRED");
        String supplier=scopes.getFirst().supplierNamespace(); if(scopes.stream().anyMatch(s->!supplier.equals(s.supplierNamespace()))) throw incomplete("MULTI_EVENT_SUPPLIER_MIXED");
        if(!approvedSupplierNamespaces.contains(supplier)) throw incomplete("MULTI_EVENT_SUPPLIER_NOT_APPROVED");
        if(scopes.stream().anyMatch(scope->scope.sourceBatchId()==null||scope.sourceBatchId().isBlank())) throw incomplete("MULTI_EVENT_SOURCE_BATCH_UNAVAILABLE");
        IdentityContract contract=identity(scopes); if(contract==null) throw incomplete("MULTI_EVENT_IDENTITY_CONTRACT_UNAVAILABLE");
        String canonical=canonicalLedgerVersionRows(scopes);
        String ledgerDigest=CandidatePipelineSupport.sha256("MEI_LEDGER_VERSION_SET",canonical);
        String policySnapshotSet=scopes.stream().sorted(Comparator.comparing((Scope s)->s.eventId().toString()).thenComparing(s->s.performanceId().toString())).map(s->s.eventId()+"|"+s.performanceId()+"|"+(s.policySnapshotId()==null?"UNAVAILABLE":s.policySnapshotId())).reduce((a,b)->a+"\n"+b).orElse("");
        String requestDigest=CandidatePipelineSupport.sha256("MEI_REQUEST",ledgerDigest,policySnapshotSet,supplier,contract.purpose(),contract.keyVersion(),input.analysisStartAt().toInstant().toString(),input.analysisEndAt().toInstant().toString(),SCORE_VERSION,"projection-v2.2-exp2-ipwindow-softhub");
        String keyDigest=CandidatePipelineSupport.sha256("MEI_IDEMPOTENCY",first.tenantId().toString(),first.membershipId().toString(),idempotencyKey);
        OffsetDateTime analysisAsOf=OffsetDateTime.now(ZoneOffset.UTC);
        return tx.execute(status->{
            List<Existing> existing=jdbc.query("select id,request_digest,status from td_multi_event_investigation_runs where tenant_id=? and requester_membership_id=? and idempotency_key_digest=?",(rs,n)->new Existing(uuid(rs.getString(1)),rs.getString(2),rs.getString(3)),first.tenantId().toString(),first.membershipId().toString(),keyDigest);
            if(!existing.isEmpty()) { Existing replay=existing.getFirst(); if(!requestDigest.equals(replay.requestDigest())) throw incomplete("IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST"); return new Accepted(replay.id(),true,replay.status()); }
            UUID run=UUID.randomUUID();
            jdbc.update("insert into td_multi_event_investigation_runs(id,tenant_id,requester_membership_id,idempotency_key_digest,request_digest,ledger_version_set_digest,supplier_namespace,hmac_purpose,hmac_key_version,score_version,projection_version,analysis_as_of,analysis_start_at,analysis_end_at,status,created_at,updated_at) values(?,?,?,?,?,?,?,'TRACK1_CONNECTION',?,'score-v2-exp2-multi-performance-recurrence','projection-v2.2-exp2-ipwindow-softhub',?,?,?,'PENDING',utc_timestamp(6),utc_timestamp(6))",run.toString(),first.tenantId().toString(),first.membershipId().toString(),keyDigest,requestDigest,ledgerDigest,supplier,contract.keyVersion(),java.sql.Timestamp.from(analysisAsOf.toInstant()),java.sql.Timestamp.from(input.analysisStartAt().toInstant()),java.sql.Timestamp.from(input.analysisEndAt().toInstant()));
            for(Scope value:scopes) jdbc.update("insert into td_multi_event_investigation_scopes(run_id,tenant_id,event_id,performance_id,ledger_version_id,external_event_ref,source_batch_id,policy_snapshot_id) values(?,?,?,?,?,?,?,?)",run.toString(),first.tenantId().toString(),value.eventId().toString(),value.performanceId().toString(),value.versionId().toString(),value.externalEventRef(),value.sourceBatchId(),value.policySnapshotId()==null?null:value.policySnapshotId().toString());
            return new Accepted(run,false,"PENDING");
        });
    }

    public Page<Run> list(UUID eventId,int page,int pageSize){
        validPage(page,pageSize);var scope=access.event(eventId,ROLES);
        String sql="select r.id,r.status,r.failure_code,r.ledger_version_set_digest,r.score_version,r.projection_version,r.analysis_algorithm_version,r.analysis_as_of,r.analysis_start_at,r.analysis_end_at,r.created_at,r.updated_at,r.completed_at from td_multi_event_investigation_runs r where r.tenant_id=? and exists (select 1 from td_multi_event_investigation_scopes anchor where anchor.run_id=r.id and anchor.event_id=?) and "+FULL_SCOPE_AUTHORIZATION+" order by r.created_at desc,r.id limit ? offset ?";
        List<Run> rows=jdbc.query(sql,(rs,n)->new Run(uuid(rs.getString(1)),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),time(rs,8),time(rs,9),time(rs,10),time(rs,11),time(rs,12),nullableTime(rs,13)),scope.tenantId().toString(),eventId.toString(),scope.tenantId().toString(),scope.membershipId().toString(),pageSize+1,page*pageSize);
        authorizeAllRunEvents(scope, rows);
        return page(rows,page,pageSize);
    }
    public Page<ScopeView> available(UUID eventId,int page,int pageSize){validPage(page,pageSize);var scope=access.event(eventId,ROLES);List<ScopeView> rows=jdbc.query("select h.event_id,h.performance_id,h.version_id,e.external_event_ref,v.source_batch_id from td_bulk_ledger_publication_heads h join td_bulk_ledger_versions v on v.id=h.version_id and v.status='PUBLISHED' join events e on e.id=h.event_id and e.tenant_id=h.tenant_id where h.tenant_id=? and h.event_id=? order by h.performance_id limit ? offset ?",(rs,n)->new ScopeView(uuid(rs.getString(1)),uuid(rs.getString(2)),uuid(rs.getString(3)),rs.getString(4),rs.getString(5)),scope.tenantId().toString(),eventId.toString(),pageSize+1,page*pageSize);return page(rows,page,pageSize);}
    public Run get(UUID eventId,UUID runId){
        var scope=access.event(eventId,ROLES);
        String sql="select r.id,r.status,r.failure_code,r.ledger_version_set_digest,r.score_version,r.projection_version,r.analysis_algorithm_version,r.analysis_as_of,r.analysis_start_at,r.analysis_end_at,r.created_at,r.updated_at,r.completed_at from td_multi_event_investigation_runs r where r.id=? and r.tenant_id=? and exists (select 1 from td_multi_event_investigation_scopes anchor where anchor.run_id=r.id and anchor.event_id=?) and "+FULL_SCOPE_AUTHORIZATION;
        Run run=jdbc.query(sql,(rs,n)->new Run(uuid(rs.getString(1)),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),time(rs,8),time(rs,9),time(rs,10),time(rs,11),time(rs,12),nullableTime(rs,13)),runId.toString(),scope.tenantId().toString(),eventId.toString(),scope.tenantId().toString(),scope.membershipId().toString()).stream().findFirst().orElseThrow(MultiEventInvestigationService::notFound);
        authorizeRunEvents(scope,runId);
        return run;
    }
    public Page<Candidate> candidates(UUID eventId,UUID runId,int page,int pageSize){validPage(page,pageSize);requireCompleted(get(eventId,runId));List<Candidate> rows=jdbc.query("select candidate_id,priority_band,investigation_score,baseline_max_score,distinct_account_count,distinct_event_count,distinct_performance_count,verified_relationship_count,score_breakdown_json,available_signals_json,excluded_signals_json,unavailable_signals_json,normal_sharing_json from td_multi_event_investigation_candidates where run_id=? order by investigation_score desc,candidate_id limit ? offset ?",(rs,n)->new Candidate(rs.getString(1),rs.getString(2),rs.getDouble(3),(Double)rs.getObject(4),rs.getInt(5),rs.getInt(6),rs.getInt(7),rs.getInt(8),rs.getString(9),rs.getString(10),rs.getString(11),rs.getString(12),rs.getString(13)),runId.toString(),pageSize+1,page*pageSize);return page(rows,page,pageSize);}
    public Page<Member> members(UUID eventId,UUID runId,String candidateId,int page,int pageSize){validPage(page,pageSize);requireCompleted(get(eventId,runId));if(candidateId==null||!candidateId.matches("multi-[a-f0-9]{64}"))throw invalid();int found=jdbc.queryForObject("select count(*) from td_multi_event_investigation_candidates where run_id=? and candidate_id=?",Integer.class,runId.toString(),candidateId);if(found!=1)throw notFound();List<Member> rows=jdbc.query("select account_reference_hmac from td_multi_event_investigation_members where run_id=? and candidate_id=? order by account_reference_hmac limit ? offset ?",(rs,n)->new Member(CandidatePipelineSupport.sha256("MEI_MEMBER",runId.toString(),candidateId,rs.getString(1)).substring(0,24)),runId.toString(),candidateId,pageSize+1,page*pageSize);return page(rows,page,pageSize);}
    public Page<Relationship> relationships(UUID eventId,UUID runId,String candidateId,int page,int pageSize){validPage(page,pageSize);requireCompleted(get(eventId,runId));if(candidateId==null||!candidateId.matches("multi-[a-f0-9]{64}"))throw invalid();int found=jdbc.queryForObject("select count(*) from td_multi_event_investigation_candidates where run_id=? and candidate_id=?",Integer.class,runId.toString(),candidateId);if(found!=1)throw notFound();List<Relationship> rows=jdbc.query("select id,signal_type,account_count,source_scope_count from td_multi_event_investigation_relationships where run_id=? and candidate_id=? order by signal_type,id limit ? offset ?",(rs,n)->{UUID id=uuid(rs.getString(1));List<String> memberHashes=jdbc.query("select account_reference_hmac from td_multi_event_investigation_relationship_members where relationship_id=? order by account_reference_hmac limit 21",(r,i)->r.getString(1),id.toString());boolean truncated=memberHashes.size()>20;if(truncated)memberHashes=memberHashes.subList(0,20);List<String> opaqueMembers=memberHashes.stream().map(account->CandidatePipelineSupport.sha256("MEI_MEMBER",runId.toString(),candidateId,account).substring(0,24)).toList();List<ScopeView> sourceScopes=jdbc.query("select s.event_id,s.performance_id,s.ledger_version_id,s.external_event_ref,s.source_batch_id from td_multi_event_investigation_relationship_sources rs join td_multi_event_investigation_scopes s on s.run_id=rs.run_id and s.performance_id=rs.performance_id where rs.relationship_id=? order by s.event_id,s.performance_id",(r,i)->new ScopeView(uuid(r.getString(1)),uuid(r.getString(2)),uuid(r.getString(3)),r.getString(4),r.getString(5)),id.toString());return new Relationship(id,rs.getString(2),rs.getInt(3),rs.getInt(4),opaqueMembers,truncated,sourceScopes);},runId.toString(),candidateId,pageSize+1,page*pageSize);return page(rows,page,pageSize);}
    public List<ScopeView> scopes(UUID eventId,UUID runId){requireCompleted(get(eventId,runId));return jdbc.query("select event_id,performance_id,ledger_version_id,external_event_ref,source_batch_id from td_multi_event_investigation_scopes where run_id=? order by event_id,performance_id",(rs,n)->new ScopeView(uuid(rs.getString(1)),uuid(rs.getString(2)),uuid(rs.getString(3)),rs.getString(4),rs.getString(5)),runId.toString());}
    public Accepted retry(UUID eventId,UUID runId){if(!enabled)throw incomplete("MULTI_EVENT_DISABLED");get(eventId,runId);
        // 퇴역 점수 버전으로 저장된 실행은 읽기 전용입니다.
        String storedVersion=jdbc.queryForObject("select score_version from td_multi_event_investigation_runs where id=?",String.class,runId.toString());
        if(!SCORE_VERSION.equals(storedVersion)) throw incomplete("SCORE_VERSION_RETIRED");int changed=jdbc.update("update td_multi_event_investigation_runs set status='PENDING',failure_code=null,updated_at=utc_timestamp(6),row_version=row_version+1 where id=? and status='RETRYABLE' and attempt_count<3 and lease_owner is null",runId.toString());if(changed!=1)throw incomplete("MULTI_EVENT_RUN_NOT_RETRYABLE");return new Accepted(runId,false,"PENDING");}

    private void authorizeAllRunEvents(CandidatePipelineAccess.Scope anchor,List<Run> runs){for(Run run:runs)authorizeRunEvents(anchor,run.id());}
    private void authorizeRunEvents(CandidatePipelineAccess.Scope anchor,UUID runId){
        List<UUID> eventIds=jdbc.query("select distinct event_id from td_multi_event_investigation_scopes where run_id=? order by event_id",(rs,n)->uuid(rs.getString(1)),runId.toString());
        if(eventIds.isEmpty()||!eventIds.contains(anchor.eventId()))throw notFound();
        for(UUID eventId:eventIds){
            CandidatePipelineAccess.Scope current=access.event(eventId,ROLES);
            if(!anchor.tenantId().equals(current.tenantId())||!anchor.membershipId().equals(current.membershipId()))throw notFound();
        }
    }

    List<Scope> scopesForWorker(UUID runId){return jdbc.query("select event_id,performance_id,ledger_version_id,external_event_ref,source_batch_id,(select supplier_namespace from td_multi_event_investigation_runs where id=s.run_id),policy_snapshot_id from td_multi_event_investigation_scopes s where run_id=? order by event_id,performance_id",(rs,n)->new Scope(uuid(rs.getString(1)),uuid(rs.getString(2)),uuid(rs.getString(3)),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7)==null?null:uuid(rs.getString(7))),runId.toString());}
    private List<Scope> heads(UUID tenant,UUID event,Set<UUID> requested){String base="select h.event_id,h.performance_id,h.version_id,e.external_event_ref,v.source_batch_id,v.source_system,(select ps.snapshot_id from td_bulk_ledger_track1_bulk_jobs pj join td_bulk_ledger_track1_policy_snapshots ps on ps.job_id=pj.id and ps.ledger_version_id=pj.ledger_version_id where pj.tenant_id=h.tenant_id and pj.event_id=h.event_id and pj.performance_id=h.performance_id and pj.ledger_version_id=h.version_id and pj.status='COMPLETED' and pj.purchase_limit_evaluations_status='COMPLETED' order by pj.completed_at desc,pj.id desc limit 1) from td_bulk_ledger_publication_heads h join td_bulk_ledger_versions v on v.id=h.version_id and v.status='PUBLISHED' join events e on e.id=h.event_id and e.tenant_id=h.tenant_id where h.tenant_id=? and h.event_id=?";if(requested.isEmpty())return jdbc.query(base+" order by h.performance_id limit 501",(rs,n)->new Scope(uuid(rs.getString(1)),uuid(rs.getString(2)),uuid(rs.getString(3)),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7)==null?null:uuid(rs.getString(7))),tenant.toString(),event.toString());String marks=String.join(",",java.util.Collections.nCopies(requested.size(),"?"));List<Object> args=new ArrayList<>(List.of(tenant.toString(),event.toString()));requested.stream().map(UUID::toString).sorted().forEach(args::add);args.add(requested.size()+1);return jdbc.query(base+" and h.performance_id in ("+marks+") order by h.performance_id limit ?",(rs,n)->new Scope(uuid(rs.getString(1)),uuid(rs.getString(2)),uuid(rs.getString(3)),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7)==null?null:uuid(rs.getString(7))),args.toArray());}
    private IdentityContract identity(List<Scope> scopes){
        String marks=String.join(",",java.util.Collections.nCopies(scopes.size(),"?"));List<Object> ids=scopes.stream().map(scope->(Object)scope.versionId().toString()).toList();
        List<VersionIdentity> rows=jdbc.query("select version_id,hmac_purpose,hmac_key_version from td_bulk_ledger_track1_ticket_signals where version_id in ("+marks+") group by version_id,hmac_purpose,hmac_key_version",(rs,n)->new VersionIdentity(uuid(rs.getString(1)),rs.getString(2),rs.getString(3)),ids.toArray());
        if(rows.size()!=scopes.size()||rows.stream().anyMatch(row->!"TRACK1_CONNECTION".equals(row.purpose()))) return null;
        Set<UUID> covered=rows.stream().map(VersionIdentity::versionId).collect(Collectors.toSet());Set<String> versions=rows.stream().map(VersionIdentity::keyVersion).collect(Collectors.toSet());
        return covered.size()==scopes.size()&&versions.size()==1?new IdentityContract("TRACK1_CONNECTION",versions.iterator().next()):null;
    }

    static String canonicalLedgerVersionRows(List<Scope> scopes){
        return scopes.stream().sorted(Comparator.comparing((Scope s)->s.eventId().toString())
                .thenComparing(s->s.performanceId().toString()))
                .map(s->s.eventId()+"|"+s.performanceId()+"|"+s.versionId())
                .reduce((a,b)->a+"\n"+b).orElseThrow();
    }
    private Claim claim(){
        List<Claim> rows=jdbc.query("""
            select id,tenant_id,fence_token,status,data_job_id,artifact_id,artifact_object_version,artifact_sha256,artifact_record_count,artifact_cancelled_ticket_count,attempt_count
            from td_multi_event_investigation_runs
            where (data_job_id is not null or attempt_count<3)
              and ((status in ('PENDING','DATA_PENDING','RETRYABLE') and lease_until is null)
                or (status='RUNNING' and lease_until<utc_timestamp(6)))
            order by created_at limit 1 for update skip locked
            """,(rs,n)->new Claim(uuid(rs.getString(1)),uuid(rs.getString(2)),rs.getLong(3)+1,rs.getString(4),
                    rs.getString(5)==null?null:uuid(rs.getString(5)),rs.getString(6),rs.getString(7),rs.getString(8),
                    nullableLong(rs,9),rs.getInt(10),rs.getInt(11)));
        if(rows.isEmpty())return null;
        Claim claim=rows.getFirst();
        int incrementAttempt=shouldIncrementAttempt(claim.priorStatus(),claim.dataJobId(),claim.artifactId())?1:0;
        int updated=jdbc.update("""
            update td_multi_event_investigation_runs set status='RUNNING',lease_owner=?,
              lease_until=date_add(utc_timestamp(6),interval 30 minute),fence_token=?,
              attempt_count=attempt_count+?,updated_at=utc_timestamp(6),row_version=row_version+1
            where id=? and fence_token=?
            """,owner(claim),claim.fence(),incrementAttempt,claim.id().toString(),claim.fence()-1);
        return updated==1?claim:null;
    }
    private void expireExhaustedLeases(){jdbc.update("update td_multi_event_investigation_runs set status='FAILED',failure_code='RETRY_LIMIT_EXCEEDED',lease_owner=null,lease_until=null,updated_at=utc_timestamp(6),row_version=row_version+1 where status='RUNNING' and data_job_id is null and attempt_count>=3 and lease_until<utc_timestamp(6)");}

    static boolean shouldIncrementAttempt(String priorStatus,UUID dataJobId,String artifactId){
        return dataJobId==null&&!("DATA_PENDING".equals(priorStatus)&&artifactId!=null);
    }

    /** Build and pin the ticket stream before asking Data to run the full multi-performance graph. */
    private void prepareArtifact(Claim claim) {
        if(storage==null) throw new Blocked("MULTI_EVENT_ARTIFACT_STORAGE_UNAVAILABLE");
        List<Scope> scopes=scopesForWorker(claim.id());
        if(scopes.size()<2) throw new Blocked("FIXED_LEDGER_SCOPE_MISSING");
        RunInput input=runInput(claim);
        Path path=null;
        try {
            path=Files.createTempFile("td-mei-", ".ndjson");
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            ArtifactWriteResult written=writeArtifactRows(claim,scopes,input,path,digest);
            long count=written.recordCount();
            if(count<1) throw new Blocked("NO_EFFECTIVE_TICKETS");
            long artifactBytes=Files.size(path);
            if(artifactBytes>artifactByteLimit()) throw new Blocked("MULTI_EVENT_ARTIFACT_TOO_LARGE");
            String sha=HexFormat.of().formatHex(digest.digest());
            String artifact="mei-"+claim.id().toString().replace("-","")+"-"+claim.fence();
            BulkLedgerUploadStorage.VerifiedObject stored;
            try(InputStream in=Files.newInputStream(path)) {
                stored=storage.putImmutable(artifact,in,artifactBytes,sha,()->heartbeat(claim));
            }
            if(stored.versionId()==null||stored.versionId().isBlank()||stored.versionId().length()>255
                    ||stored.byteSize()!=Files.size(path)||!sha.equals(stored.sha256()))
                throw new Blocked("MULTI_EVENT_ARTIFACT_PROOF_INVALID");
            tx.executeWithoutResult(status->{
                requireLease(claim);
                int changed=jdbc.update("""
                    update td_multi_event_investigation_runs
                    set artifact_id=?,artifact_object_version=?,artifact_sha256=?,artifact_record_count=?,artifact_cancelled_ticket_count=?,
                        status='DATA_PENDING',lease_owner=null,lease_until=null,failure_code=null,
                        updated_at=utc_timestamp(6),row_version=row_version+1
                    where id=? and status='RUNNING' and fence_token=? and lease_owner=?
                    """,artifact,stored.versionId(),stored.sha256(),count,written.cancelledTicketRecordCount(),
                    claim.id().toString(),claim.fence(),owner(claim));
                if(changed!=1) throw new Blocked("RUN_LEASE_LOST");
            });
        } catch(Blocked e){throw e;}
        catch(BulkLedgerUploadStorage.InvalidObject|BulkLedgerUploadStorage.ObjectChanged e){throw e;}
        catch(Exception e){throw new RuntimeException("Multi-event artifact preparation failed",e);}
        finally {if(path!=null)try{Files.deleteIfExists(path);}catch(Exception ignored){}}
    }

    private ArtifactWriteResult writeArtifactRows(Claim claim,List<Scope> scopes,RunInput input,Path path,MessageDigest digest) throws Exception {
        long[] total={0},byteTotal={0};
        int[] cancelledTotal={0};
        try(var output=new BufferedOutputStream(Files.newOutputStream(path))) {
            for(Scope scope:scopes) {
                long[] ordinal={0};
                int queryLimit=(int)Math.min((long)MAX_ARTIFACT_TICKETS-total[0]+1,Integer.MAX_VALUE);
                jdbc.query(connection->{
                    PreparedStatement statement=connection.prepareStatement(ARTIFACT_TICKET_SQL,ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY);
                    statement.setFetchSize(MYSQL_STREAMING_FETCH_SIZE);
                    statement.setString(1,scope.versionId().toString());
                    statement.setString(2,scope.versionId().toString());
                    statement.setString(3,scope.policySnapshotId()==null?null:scope.policySnapshotId().toString());
                    statement.setString(4,scope.versionId().toString());
                    statement.setString(5,scope.performanceId().toString());
                    statement.setTimestamp(6,java.sql.Timestamp.from(input.analysisStartAt().toInstant()));
                    statement.setTimestamp(7,java.sql.Timestamp.from(input.analysisEndAt().toInstant()));
                    statement.setInt(8,queryLimit);
                    return statement;
                },rs->{
                    try {
                        if(total[0]>=MAX_ARTIFACT_TICKETS) throw new Blocked("MULTI_EVENT_TICKET_LIMIT_EXCEEDED");
                        MultiEventAnalysisDto.Ticket ticket=ticketFromResultSet(rs,scope,input,++ordinal[0]);
                        byte[] bytes=(json.writeValueAsString(ticket)+"\n").getBytes(StandardCharsets.UTF_8);
                        if(bytes.length>artifactByteLimit()-byteTotal[0]) throw new Blocked("MULTI_EVENT_ARTIFACT_TOO_LARGE");
                        output.write(bytes); digest.update(bytes); total[0]++;
                        byteTotal[0]+=bytes.length;
                    if(total[0]%10_000==0) heartbeat(claim);
                    } catch(Blocked e){throw e;} catch(Exception e){throw new RuntimeException(e);}
                });
                if(cancelledTicketTransferEnabled) {
                    int cancellationLimit=(int)Math.min((long)MAX_ARTIFACT_TICKETS-total[0]+1,Integer.MAX_VALUE);
                    jdbc.query(connection->{
                        PreparedStatement statement=connection.prepareStatement(ARTIFACT_CANCELLED_TICKET_SQL,
                                ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY);
                        statement.setFetchSize(MYSQL_STREAMING_FETCH_SIZE);
                        statement.setString(1,scope.versionId().toString());
                        statement.setTimestamp(2,java.sql.Timestamp.from(input.analysisStartAt().toInstant()));
                        statement.setTimestamp(3,java.sql.Timestamp.from(input.analysisEndAt().toInstant()));
                        statement.setString(4,input.keyVersion());
                        statement.setInt(5,cancellationLimit);
                        return statement;
                    },rs->{
                        try {
                            if(total[0]>=MAX_ARTIFACT_TICKETS) throw new Blocked("MULTI_EVENT_TICKET_LIMIT_EXCEEDED");
                            MultiEventAnalysisDto.CancelledTicket cancelled=cancelledTicketFromResultSet(rs,scope,input,++ordinal[0]);
                            byte[] bytes=(json.writeValueAsString(new MultiEventAnalysisDto.ArtifactCancelledTicketRecord(
                                    "CANCELLED_TICKET",cancelled))+"\n").getBytes(StandardCharsets.UTF_8);
                            if(bytes.length>artifactByteLimit()-byteTotal[0]) throw new Blocked("MULTI_EVENT_ARTIFACT_TOO_LARGE");
                            output.write(bytes); digest.update(bytes); total[0]++; cancelledTotal[0]++;
                            byteTotal[0]+=bytes.length;
                            if(total[0]%10_000==0) heartbeat(claim);
                        } catch(Blocked e){throw e;} catch(Exception e){throw new RuntimeException(e);}
                    });
                }
            }
        }
        return new ArtifactWriteResult(total[0],cancelledTotal[0]);
    }

    private static final String ARTIFACT_TICKET_SQL="""
        with recursive l as (select id,base_version_id,generation_no,revision_no from td_bulk_ledger_versions where id=? and status='PUBLISHED'
          union all select p.id,p.base_version_id,p.generation_no,p.revision_no from td_bulk_ledger_versions p join l on l.base_version_id=p.id where p.status='PUBLISHED' and p.generation_no=l.generation_no and p.revision_no=l.revision_no-1),
        ranked as (select tv.*,row_number() over(partition by tv.ticket_reference_hmac order by l.revision_no desc) rn from td_bulk_ledger_ticket_versions tv join l on l.id=tv.version_id)
        select r.reservation_reference_hmac,r.account_reference_hmac,r.purchased_at,r.hmac_key_version,r.source_version,
               s.payment_hmac,s.address_hmac,s.device_hmac,s.ip_hmac,s.refund_account_hmac,s.phone_hmac,s.ip_network_type,s.hmac_purpose,s.hmac_key_version,s.source_version,
               ps.snapshot_id,ps.source_version,ps.ledger_complete_snapshot,ps.policy_verification_status,ps.quantity_data_status,ps.data_completeness_reason,ps.cancellation_handling,ps.duplicate_handling,ps.max_tickets_per_account,
               lf.effective_ticket_quantity,lf.max_tickets_per_account,lf.policy_version,
               r.seat_zone,r.seat_row,r.seat_number,r.reserved_at,r.paid_at,r.account_created_at
        from ranked r left join td_bulk_ledger_track1_ticket_signals s on s.version_id=? and s.ticket_reference_hmac=r.ticket_reference_hmac
        left join td_bulk_ledger_track1_policy_snapshots ps on ps.snapshot_id=? and ps.ledger_version_id=?
        left join td_bulk_ledger_track1_bulk_limit_findings lf on lf.job_id=ps.job_id and lf.performance_id=? and lf.account_reference_hmac=r.account_reference_hmac
        where r.rn=1 and r.ticket_status='ACTIVE' and r.purchased_at>=? and r.purchased_at<?
        order by r.ticket_reference_hmac limit ?
        """;

    private static final String ARTIFACT_CANCELLED_TICKET_SQL="""
        with recursive l as (select id,base_version_id,generation_no,revision_no from td_bulk_ledger_versions where id=? and status='PUBLISHED'
          union all select p.id,p.base_version_id,p.generation_no,p.revision_no from td_bulk_ledger_versions p join l on l.base_version_id=p.id where p.status='PUBLISHED' and p.generation_no=l.generation_no and p.revision_no=l.revision_no-1),
        ranked as (select tv.*,row_number() over(partition by tv.ticket_reference_hmac order by l.revision_no desc) rn from td_bulk_ledger_ticket_versions tv join l on l.id=tv.version_id)
        select r.ticket_reference_hmac,r.account_reference_hmac,r.hmac_key_version,r.source_version,
               r.seat_zone,r.seat_row,r.seat_number,r.cancelled_at
        from ranked r
        where r.rn=1 and r.ticket_status in ('CANCELLED','REFUNDED') and r.cancelled_at is not null
          and r.purchased_at>=? and r.purchased_at<? and r.hmac_key_version=?
        order by r.ticket_reference_hmac limit ?
        """;

    private MultiEventAnalysisDto.CancelledTicket cancelledTicketFromResultSet(ResultSet rs,Scope scope,
            RunInput input,long ordinal) throws SQLException {
        String ticketHmac=rs.getString(1),accountHmac=rs.getString(2),keyVersion=rs.getString(3),sourceVersion=rs.getString(4);
        Timestamp cancelledAt=rs.getTimestamp(8);
        if(ordinal<1||ordinal>MAX_ARTIFACT_TICKETS||ticketHmac==null||!ticketHmac.matches("[0-9a-f]{64}")
                ||accountHmac==null||!accountHmac.matches("[0-9a-f]{64}")||!input.keyVersion().equals(keyVersion)
                ||sourceVersion==null||sourceVersion.isBlank()||cancelledAt==null)
            throw new Blocked("CANCELLED_TICKET_IDENTITY_INVALID");
        return new MultiEventAnalysisDto.CancelledTicket(accountHmac,ticketHmac,scope.externalEventRef(),
                scope.performanceId(),scope.versionId(),input.supplierNamespace(),input.purpose(),keyVersion,sourceVersion,
                ordinal,rs.getString(5),rs.getString(6),rs.getString(7),cancelledAt.toLocalDateTime().atOffset(ZoneOffset.UTC));
    }

    private MultiEventAnalysisDto.Ticket ticketFromResultSet(ResultSet rs,Scope scope,RunInput input,long ordinal) throws Exception {
        String rowKey=rs.getString(4),rowSource=rs.getString(5),purpose=rs.getString(13),key=rs.getString(14),signalSource=rs.getString(15);
        String account=rs.getString(2),reservation=rs.getString(1);
        if(ordinal<1||ordinal>MAX_ARTIFACT_TICKETS||!input.keyVersion().equals(rowKey)||!input.keyVersion().equals(key)
                ||!"TRACK1_CONNECTION".equals(purpose)||rowSource==null||!rowSource.equals(signalSource)
                ||account==null||reservation==null||!account.matches("[0-9a-f]{64}")||!reservation.matches("[0-9a-f]{64}")
                ||(rs.getString(6)==null&&rs.getString(7)==null&&rs.getString(8)==null&&rs.getString(9)==null&&rs.getString(10)==null&&rs.getString(11)==null))
            throw new Blocked("MULTI_EVENT_SIGNAL_OR_IDENTITY_INVALID");
        String quantityStatus="POLICY_NOT_CONFIRMED",quantityReason="POLICY_SNAPSHOT_UNAVAILABLE";
        Integer quantity=null,confirmedLimit=null; UUID quantitySnapshot=null; String policyVersion=null,quantitySourceVersion=null;
        String snapshotId=rs.getString(16),policySourceVersion=rs.getString(17),policyStatus=rs.getString(19),quantityDataStatus=rs.getString(20),cancellationHandling=rs.getString(22),duplicateHandling=rs.getString(23),snapshotPolicyVersion=rs.getString(27);
        long effectiveQuantity=rs.getLong(25);boolean quantityFound=!rs.wasNull();int policyLimit=rs.getInt(24);boolean policyLimitFound=!rs.wasNull();int findingLimit=rs.getInt(26);boolean findingLimitFound=!rs.wasNull();
        if(snapshotId!=null){
            quantitySnapshot=uuid(snapshotId);quantitySourceVersion=policySourceVersion;policyVersion=snapshotPolicyVersion;
            if(!"CONFIRMED".equals(policyStatus)) quantityReason="POLICY_NOT_CONFIRMED";
            else if(!"COMPLETE".equals(quantityDataStatus)||!rs.getBoolean(18)||"GROSS".equals(cancellationHandling)||!"DEDUPLICATED".equals(duplicateHandling)) {quantityStatus="INCOMPLETE";quantityReason=rs.getString(21)==null?"POLICY_QUANTITY_SOURCE_INCOMPLETE":rs.getString(21);}
            else if(policySourceVersion==null||!policySourceVersion.equals(rowSource)||!quantityFound||!policyLimitFound||!findingLimitFound||policyLimit!=findingLimit||snapshotPolicyVersion==null||!snapshotPolicyVersion.equals(rs.getString(27))||effectiveQuantity<1||effectiveQuantity>100||policyLimit<1||policyLimit>100) {quantityStatus="INCOMPLETE";quantityReason="POLICY_QUANTITY_SOURCE_MISMATCH";}
            else {quantityStatus="COMPLETE";quantityReason="POLICY_SNAPSHOT_AND_QUANTITY_VERIFIED";quantity=(int)effectiveQuantity;confirmedLimit=policyLimit;}
        }
        java.sql.Timestamp purchased=rs.getTimestamp(3);
        return new MultiEventAnalysisDto.Ticket(account,reservation,scope.externalEventRef(),scope.performanceId(),scope.versionId(),ordinal,
                input.supplierNamespace(),purpose,key,purchased==null?null:purchased.toLocalDateTime().atOffset(ZoneOffset.UTC),quantity,confirmedLimit,
                quantitySnapshot,policyVersion,quantitySourceVersion,rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9),rs.getString(10),
                rs.getString(11),rs.getString(12),quantityStatus,quantityReason,rowSource,
                rs.getString(28),rs.getString(29),rs.getString(30),nullableTime(rs,31),nullableTime(rs,32),nullableTime(rs,33));
    }

    private void registerArtifact(Claim claim) {
        if(claim.artifactId()==null||claim.objectVersion()==null||claim.artifactSha256()==null||claim.artifactRecordCount()==null)
            throw new Blocked("MULTI_EVENT_ARTIFACT_METADATA_MISSING");
        List<Scope> scopes=scopesForWorker(claim.id());
        RunInput input=runInput(claim);
        String inputFormat=claim.artifactCancelledTicketCount()>0
                ?"multi-event-exp2-ndjson-v2":"multi-event-exp2-ndjson-v1";
        var manifest=new MultiEventAnalysisDto.ArtifactManifest(claim.artifactId(),claim.artifactId(),claim.objectVersion(),claim.artifactSha256(),
                claim.artifactRecordCount(),claim.artifactCancelledTicketCount(),inputFormat,claim.id(),claim.tenantId(),input.supplierNamespace(),input.purpose(),
                input.keyVersion(),input.ledgerDigest(),input.analysisAsOf(),input.analysisStartAt(),input.analysisEndAt(),input.scoreVersion(),input.projectionVersion(),
                scopes.stream().sorted(Comparator.comparing((Scope s)->s.eventId().toString()).thenComparing(s->s.performanceId().toString())).map(s->
                        new MultiEventAnalysisDto.ArtifactLedgerScope(s.externalEventRef(),s.eventId(),s.performanceId(),s.versionId(),s.sourceBatchId(),s.policySnapshotId())).toList());
        var accepted=data.createMultiEventJob(new MultiEventAnalysisDto.ArtifactCreate(manifest),dataJobIdempotencyKey(claim.id()));
        if(accepted==null||accepted.jobId()==null||!Set.of("QUEUED","RUNNING","COMPLETED","FAILED","INPUT_REJECTED","INTERRUPTED").contains(accepted.status()))
            throw new FastApiResponseContractException("Data returned an invalid multi-event job registration");
        tx.executeWithoutResult(status->{
            requireLease(claim);
            int changed=jdbc.update("update td_multi_event_investigation_runs set data_job_id=?,status='DATA_PENDING',failure_code=null,lease_owner=null,lease_until=null,updated_at=utc_timestamp(6),row_version=row_version+1 where id=? and status='RUNNING' and fence_token=? and lease_owner=?",
                    accepted.jobId().toString(),claim.id().toString(),claim.fence(),owner(claim));
            if(changed!=1)throw new Blocked("RUN_LEASE_LOST");
        });
    }

    static String dataJobIdempotencyKey(UUID runId){return "mei-"+runId;}

    private RunInput runInput(Claim claim){
        return jdbc.query("select ledger_version_set_digest,supplier_namespace,hmac_purpose,hmac_key_version,score_version,projection_version,analysis_as_of,analysis_start_at,analysis_end_at from td_multi_event_investigation_runs where id=? and tenant_id=?",
                (rs,n)->new RunInput(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),time(rs,7),time(rs,8),time(rs,9)),
                claim.id().toString(),claim.tenantId().toString()).stream().findFirst().orElseThrow(()->new Blocked("RUN_MISSING"));
    }

    private void heartbeat(Claim claim){
        int changed=jdbc.update("update td_multi_event_investigation_runs set lease_until=date_add(utc_timestamp(6),interval 30 minute),updated_at=utc_timestamp(6) where id=? and status='RUNNING' and fence_token=? and lease_owner=?",
                claim.id().toString(),claim.fence(),owner(claim));
        if(changed!=1)throw new Blocked("RUN_LEASE_LOST");
    }

    private long artifactByteLimit(){return maxArtifactBytes<1?0:Math.min(maxArtifactBytes,DEFAULT_MAX_ARTIFACT_BYTES);}

    private void pollAndCollect(Claim claim) {
        MultiEventAnalysisDto.ArtifactStatus status=data.multiEventJobStatus(claim.dataJobId());
        if(status==null||!claim.dataJobId().equals(status.jobId())) throw new FastApiResponseContractException("Data returned a mismatched multi-event job status");
            if(!Objects.equals(runInput(claim).ledgerDigest(),status.ledgerVersionSetDigest())
                    ||status.inputRecordCount()<0||status.processedRecordCount()<0||status.processedRecordCount()>status.inputRecordCount()
                    ||(status.candidateCount()!=null&&(status.candidateCount()<0||status.candidateCount()>10_000)))
            throw new FastApiResponseContractException("Data returned invalid multi-event job counters");
        switch(status.status()) {
            case "QUEUED", "RUNNING" -> markDataPending(claim);
            case "COMPLETED" -> {
                if(claim.artifactRecordCount()==null||status.inputRecordCount()!=claim.artifactRecordCount()
                        ||status.processedRecordCount()!=claim.artifactRecordCount()||status.candidateCount()==null)
                    throw new FastApiResponseContractException("Data did not process the complete fixed artifact");
                if(status.algorithmVersion()==null||!status.algorithmVersion().matches("^gds-[0-9]+(?:\\.[0-9]+){1,2}$"))
                    throw new FastApiResponseContractException("Data returned an invalid GDS algorithm version");
                int candidates=collectResultPages(claim,status.candidateCount());
                completeArtifactRun(claim,status.algorithmVersion(),candidates);
            }
            case "FAILED", "INTERRUPTED" -> {
                String failureCode=status.failureCode();
                if(failureCode!=null&&Set.of("MULTI_EVENT_CANDIDATE_MEMBER_ROW_BUDGET_EXCEEDED",
                        "MULTI_EVENT_RELATIONSHIP_MEMBER_ROW_BUDGET_EXCEEDED",
                        "CANDIDATE_EVIDENCE_BUDGET_EXCEEDED").contains(failureCode))
                    throw new Blocked(failureCode);
                retryDataJob(claim);
            }
            case "INPUT_REJECTED" -> throw new Blocked("DATA_INPUT_REJECTED");
            case "CANCELLED" -> throw new Blocked("DATA_JOB_CANCELLED");
            default -> throw new FastApiResponseContractException("Data returned an unknown multi-event job status");
        }
    }

    void retryDataJob(Claim claim) {
        if(claim.attemptCount()>=3) throw new Blocked("DATA_RETRY_LIMIT_EXCEEDED");
        var accepted=data.retryMultiEventJob(claim.dataJobId());
        if(accepted==null||!claim.dataJobId().equals(accepted.jobId())
                ||!Set.of("QUEUED","RUNNING").contains(accepted.status()))
            throw new FastApiResponseContractException("Data did not accept a retry for the fixed job");
        tx.executeWithoutResult(status->{
            requireLease(claim);
            int changed=jdbc.update("update td_multi_event_investigation_runs set status='DATA_PENDING',attempt_count=attempt_count+1,lease_owner=null,lease_until=null,failure_code=null,updated_at=utc_timestamp(6),row_version=row_version+1 where id=? and status='RUNNING' and fence_token=? and lease_owner=? and attempt_count<3",
                    claim.id().toString(),claim.fence(),owner(claim));
            if(changed!=1)throw new Blocked("RUN_LEASE_LOST");
        });
    }

    private void markDataPending(Claim claim) {
        tx.executeWithoutResult(status->{
            requireLease(claim);
            int changed=jdbc.update("update td_multi_event_investigation_runs set status='DATA_PENDING',lease_owner=null,lease_until=null,updated_at=utc_timestamp(6),row_version=row_version+1 where id=? and status='RUNNING' and fence_token=? and lease_owner=?",
                    claim.id().toString(),claim.fence(),owner(claim));
            if(changed!=1)throw new Blocked("RUN_LEASE_LOST");
        });
    }

    void markQueueFullPending(Claim claim){
        tx.executeWithoutResult(status->{
            requireLease(claim);
            int changed=jdbc.update("update td_multi_event_investigation_runs set status='DATA_PENDING',failure_code=null,lease_owner=null,lease_until=null,updated_at=utc_timestamp(6),row_version=row_version+1 where id=? and status='RUNNING' and fence_token=? and lease_owner=?",
                    claim.id().toString(),claim.fence(),owner(claim));
            if(changed!=1)throw new Blocked("RUN_LEASE_LOST");
        });
    }

    private int collectResultPages(Claim claim,int expectedCandidates) {
        List<Scope> scopes=scopesForWorker(claim.id());
        RunInput input=runInput(claim);
        Map<String,MultiEventAnalysisDto.LedgerScope> fixedScopes=new java.util.HashMap<>();
        for(Scope scope:scopes) {
            var dto=new MultiEventAnalysisDto.LedgerScope(scope.externalEventRef(),scope.performanceId(),scope.versionId(),scope.sourceBatchId(),scope.policySnapshotId());
            fixedScopes.put(scopeKey(dto),dto);
        }
        Map<String,CandidateBudget> budgets=preflightCandidatePages(claim,input,fixedScopes,expectedCandidates);
        tx.executeWithoutResult(txStatus->{
            requireLease(claim);
            jdbc.update("delete from td_multi_event_investigation_relationship_sources where run_id=?",claim.id().toString());
            jdbc.update("delete from td_multi_event_investigation_relationship_members where run_id=?",claim.id().toString());
            jdbc.update("delete from td_multi_event_investigation_relationships where run_id=?",claim.id().toString());
            jdbc.update("delete from td_multi_event_investigation_members where run_id=?",claim.id().toString());
            jdbc.update("delete from td_multi_event_investigation_candidates where run_id=?",claim.id().toString());
        });
        String cursor=null;
        Set<String> seenCursors=new HashSet<>(),seenCandidates=new HashSet<>();
        int total=-1,received=0;
        do {
            MultiEventAnalysisDto.CandidatePage page=data.multiEventCandidatePage(claim.dataJobId(),cursor);
            validateCandidateEnvelope(page,claim,input.ledgerDigest(),cursor,total);
            if(page.totalCount()!=expectedCandidates)
                throw new FastApiResponseContractException("Data candidate total does not match completed job status");
            if(total<0) {
                total=page.totalCount();
                if(total>10_000) throw new FastApiResponseContractException("Data returned too many multi-event candidates");
            }
            for(var candidate:page.records()) {
                if(candidate==null||candidate.candidateId()==null||!seenCandidates.add(candidate.candidateId()))
                    throw new FastApiResponseContractException("Data returned a duplicate or invalid candidate page row");
                validateCandidateSummary(candidate,fixedScopes);
                CandidateBudget budget=budgets.get(candidate.candidateId());
                if(budget==null||budget.memberCount()!=candidate.distinctAccountCount())
                    throw new FastApiResponseContractException("Data candidate changed after row-budget preflight");
                insertCandidateSummary(claim,candidate);
                int memberCount=collectMembers(claim,candidate,fixedScopes,input);
                if(memberCount!=candidate.distinctAccountCount()) throw new FastApiResponseContractException("Data candidate member pages are incomplete");
                verifyAvailableSignals(claim,candidate,input);
                Double baseline=baselineMaxScore(claim.id(),candidate.candidateId(),candidate.distinctAccountCount(),candidate.sourceScopes());
                tx.executeWithoutResult(txStatus->{
                    requireLease(claim);
                    jdbc.update("update td_multi_event_investigation_candidates set baseline_max_score=? where run_id=? and candidate_id=?",
                            baseline,claim.id().toString(),candidate.candidateId());
                });
                RelationshipImportSummary relationshipImport=collectRelationships(claim,candidate,fixedScopes,input);
                if(relationshipImport.observations()!=candidate.verifiedRelationshipCount()
                        ||relationshipImport.memberRows()!=budget.relationshipMemberRows())
                    throw new FastApiResponseContractException("Data relationship pages do not match candidate source counts");
                if(received>=10_000) throw new FastApiResponseContractException("Data returned too many multi-event candidates");
                received++;
            }
            cursor=page.nextCursor();
            if(cursor!=null&&!seenCursors.add(cursor)) throw new FastApiResponseContractException("Data repeated a multi-event candidate cursor");
        } while(cursor!=null);
        if(total<0||received!=total||!seenCandidates.equals(budgets.keySet()))
            throw new FastApiResponseContractException("Data candidate page totals are incomplete or changed after preflight");
        Integer stored=jdbc.queryForObject("select count(*) from td_multi_event_investigation_candidates where run_id=?",Integer.class,claim.id().toString());
        if(stored==null||stored!=total) throw new FastApiResponseContractException("Stored multi-event candidate count does not match Data");
        return total;
    }

    private Map<String,CandidateBudget> preflightCandidatePages(Claim claim,RunInput input,
            Map<String,MultiEventAnalysisDto.LedgerScope> fixedScopes,int expectedCandidates) {
        Map<String,CandidateBudget> budgets=new java.util.HashMap<>();
        Set<String> seenCursors=new HashSet<>();
        String cursor=null;
        int total=-1,received=0;
        long memberRows=0,relationshipMemberRows=0;
        do {
            MultiEventAnalysisDto.CandidatePage page=data.multiEventCandidatePage(claim.dataJobId(),cursor);
            validateCandidateEnvelope(page,claim,input.ledgerDigest(),cursor,total);
            if(page.totalCount()!=expectedCandidates)
                throw new FastApiResponseContractException("Data candidate total does not match completed job status");
            if(total<0)total=page.totalCount();
            for(var candidate:page.records()) {
                if(candidate==null||candidate.candidateId()==null||budgets.containsKey(candidate.candidateId()))
                    throw new FastApiResponseContractException("Data returned a duplicate or invalid candidate during preflight");
                validateCandidateSummary(candidate,fixedScopes);
                if(candidate.distinctAccountCount()>MAX_IMPORTED_MEMBER_ROWS-memberRows)
                    throw new Blocked("MULTI_EVENT_CANDIDATE_MEMBER_ROW_BUDGET_EXCEEDED");
                memberRows+=candidate.distinctAccountCount();
                long candidateRelationshipRows=preflightRelationshipPages(claim,candidate,input,fixedScopes);
                if(candidateRelationshipRows>MAX_IMPORTED_RELATIONSHIP_MEMBER_ROWS-relationshipMemberRows)
                    throw new Blocked("MULTI_EVENT_RELATIONSHIP_MEMBER_ROW_BUDGET_EXCEEDED");
                relationshipMemberRows+=candidateRelationshipRows;
                budgets.put(candidate.candidateId(),new CandidateBudget(candidate.distinctAccountCount(),candidateRelationshipRows));
                received++;
            }
            cursor=page.nextCursor();
            if(cursor!=null&&!seenCursors.add(cursor))
                throw new FastApiResponseContractException("Data repeated a multi-event candidate preflight cursor");
        } while(cursor!=null);
        if(total!=expectedCandidates||received!=expectedCandidates)
            throw new FastApiResponseContractException("Data candidate preflight totals are incomplete");
        return Map.copyOf(budgets);
    }

    private long preflightRelationshipPages(Claim claim,MultiEventAnalysisDto.CandidateSummary candidate,RunInput input,
            Map<String,MultiEventAnalysisDto.LedgerScope> fixedScopes) {
        String cursor=null;
        Set<String> seenCursors=new HashSet<>(),signals=new HashSet<>(),relationshipIds=new HashSet<>();
        int total=-1,received=0;
        long memberRows=0;
        do {
            MultiEventAnalysisDto.RelationshipPage page=data.multiEventRelationshipPage(claim.dataJobId(),candidate.candidateId(),cursor);
            if(page==null||!claim.dataJobId().equals(page.jobId())||!candidate.candidateId().equals(page.candidateId())
                    ||!Objects.equals(input.ledgerDigest(),page.ledgerVersionSetDigest())||!Objects.equals(cursor,page.cursor())
                    ||page.totalCount()<0||page.totalCount()>RELATIONSHIP_TYPES.size()||(total>=0&&total!=page.totalCount())
                    ||page.records()==null||page.records().size()>MEMBER_PAGE_SIZE)
                throw new FastApiResponseContractException("Data returned an invalid multi-event relationship preflight page");
            if(total<0)total=page.totalCount();
            validatePageCursor(cursor,page.nextCursor(),total,page.records().size());
            for(var relation:page.records()) {
                validateRelationshipSummary(candidate,relation,fixedScopes);
                if(!signals.add(relation.signalType())||!relationshipIds.add(relation.relationshipId()))
                    throw new FastApiResponseContractException("Data returned duplicate relationship evidence during preflight");
                memberRows+=relation.accountCount();
                received++;
            }
            cursor=page.nextCursor();
            if(cursor!=null&&!seenCursors.add(cursor))
                throw new FastApiResponseContractException("Data repeated a relationship preflight cursor");
        } while(cursor!=null);
        if(total<0||received!=total||!relationshipSignalsConsistent(signals,candidate))
            throw new FastApiResponseContractException("Data relationship preflight does not match candidate signals");
        return memberRows;
    }

    private void completeArtifactRun(Claim claim,String algorithmVersion,int expectedCandidates) {
        tx.executeWithoutResult(status->{
            requireLease(claim);
            Integer candidates=jdbc.queryForObject("select count(*) from td_multi_event_investigation_candidates where run_id=?",Integer.class,claim.id().toString());
            if(candidates==null||candidates!=expectedCandidates) throw new Blocked("RESULT_CANDIDATE_COUNT_MISMATCH");
            int updated=jdbc.update("update td_multi_event_investigation_runs set status='COMPLETED',failure_code=null,analysis_algorithm_version=?,lease_owner=null,lease_until=null,completed_at=utc_timestamp(6),updated_at=utc_timestamp(6),row_version=row_version+1 where id=? and status='RUNNING' and fence_token=? and lease_owner=?",
                    algorithmVersion,claim.id().toString(),claim.fence(),owner(claim));
            if(updated!=1)throw new Blocked("RUN_LEASE_LOST");
        });
    }

    private static void requireCompleted(Run run){if(!"COMPLETED".equals(run.status()))throw incomplete("MULTI_EVENT_RESULTS_NOT_AVAILABLE");}

    private void insertCandidateSummary(Claim claim,MultiEventAnalysisDto.CandidateSummary candidate) {
        String breakdown=write(candidate.scoreBreakdown()),available=write(candidate.availableSignals()),excluded=write(candidate.excludedSignals()),unavailable=write(candidate.unavailableSignals()),normal=write(candidate.normalSharingPossibilities());
        tx.executeWithoutResult(status->{
            requireLease(claim);
            jdbc.update("insert into td_multi_event_investigation_candidates(run_id,candidate_id,priority_band,investigation_score,baseline_max_score,distinct_account_count,distinct_event_count,distinct_performance_count,verified_relationship_count,score_breakdown_json,available_signals_json,excluded_signals_json,unavailable_signals_json,normal_sharing_json) values(?,?,?,?,null,?,?,?,?,cast(? as json),cast(? as json),cast(? as json),cast(? as json),cast(? as json))",
                    claim.id().toString(),candidate.candidateId(),candidate.priorityBand(),candidate.scoreBreakdown().score(),candidate.distinctAccountCount(),candidate.distinctEventCount(),candidate.distinctPerformanceCount(),candidate.verifiedRelationshipCount(),breakdown,available,excluded,unavailable,normal);
        });
    }

    private void validateCandidateEnvelope(MultiEventAnalysisDto.CandidatePage page,Claim claim,
            String ledgerDigest,String requestedCursor,int priorTotal) {
        if(page==null||!claim.dataJobId().equals(page.jobId())
                ||!Objects.equals(ledgerDigest,page.ledgerVersionSetDigest())
                ||!Objects.equals(requestedCursor,page.cursor())||page.totalCount()<0||page.totalCount()>10_000
                ||(priorTotal>=0&&page.totalCount()!=priorTotal)||page.records()==null||page.records().size()>RESULT_PAGE_SIZE)
            throw new FastApiResponseContractException("Data returned an invalid multi-event candidate page envelope");
        validatePageCursor(requestedCursor,page.nextCursor(),page.totalCount(),page.records().size());
    }

    void validateCandidateSummary(MultiEventAnalysisDto.CandidateSummary candidate,
            Map<String,MultiEventAnalysisDto.LedgerScope> fixedScopes) {
        if(candidate.candidateId()==null||!candidate.candidateId().matches("multi-[a-f0-9]{64}")
                ||!SCORE_VERSION.equals(candidate.scoreVersion())
                ||!"projection-v2.2-exp2-ipwindow-softhub".equals(candidate.projectionVersion())
                ||!Set.of("HIGH","MEDIUM","LOW").contains(candidate.priorityBand())
                ||candidate.distinctAccountCount()<2||candidate.distinctAccountCount()>50_000
                ||candidate.distinctEventCount()<1||candidate.distinctPerformanceCount()<2
                ||candidate.verifiedRelationshipCount()<1||candidate.sourceScopes()==null||candidate.sourceScopes().size()<2
                ||candidate.availableSignals()==null||candidate.excludedSignals()==null||candidate.unavailableSignals()==null
                ||candidate.normalSharingPossibilities()==null||candidate.scoreBreakdown()==null
                ||!sourceScopesValid(candidate.sourceScopes(),fixedScopes,null))
            throw new FastApiResponseContractException("Data returned an invalid multi-event candidate summary");
        long events=candidate.sourceScopes().stream().map(MultiEventAnalysisDto.LedgerScope::eventId).distinct().count();
        long performances=candidate.sourceScopes().stream().map(MultiEventAnalysisDto.LedgerScope::performanceId).distinct().count();
        if(events!=candidate.distinctEventCount()||performances!=candidate.distinctPerformanceCount())
            throw new FastApiResponseContractException("Data candidate counts do not match its fixed source scopes");
        // 점수 구성 신호는 exp2 신호 10종이며 결제와 망 유형별 IP 시간창을 포함합니다.
        if(candidate.availableSignals().isEmpty()||candidate.availableSignals().stream().anyMatch(s->!EXP2_SIGNALS.contains(s))
                ||candidate.availableSignals().size()!=Set.copyOf(candidate.availableSignals()).size()
                ||candidate.excludedSignals().stream().anyMatch(s->!EXP2_SIGNALS.contains(s))
                ||candidate.unavailableSignals().stream().anyMatch(s->!UNAVAILABLE_SIGNALS.contains(s))
                ||candidate.unavailableSignals().stream().anyMatch(candidate.availableSignals()::contains))
            throw new FastApiResponseContractException("Data candidate signal lists are invalid");
        var score=candidate.scoreBreakdown();
        double[] parts={score.L(),score.G(),score.B(),score.Q(),score.R(),score.score()};
        for(double value:parts)if(!Double.isFinite(value)||value<0||value>1)
            throw new FastApiResponseContractException("Data candidate score is outside its valid range");
        // 사전 등록 정의: R = min((서로 다른 회차 수 - 1) / 3, 1)
        double expectedRecurrence=Math.min((candidate.distinctPerformanceCount()-1)/3.0,1.0);
        double expectedBehavior=.5*score.Q()+.5*expectedRecurrence;
        double expectedScore=Math.round(score.L()*score.G()*score.B()*10_000.0)/10_000.0;
        if(Math.abs(score.R()-expectedRecurrence)>1e-9||Math.abs(score.B()-expectedBehavior)>1e-9
                ||Math.abs(score.score()-expectedScore)>1e-9
                ||!Set.of("VERIFIED","NEUTRAL_NO_POLICY").contains(score.quantityStatus()))
            throw new FastApiResponseContractException("Data candidate score breakdown is inconsistent");
        String band=score.score()>=.40?"HIGH":score.score()>=.20?"MEDIUM":"LOW";
        if(!band.equals(candidate.priorityBand())) throw new FastApiResponseContractException("Data candidate priority band is inconsistent");
    }

    private int collectMembers(Claim claim,MultiEventAnalysisDto.CandidateSummary candidate,
            Map<String,MultiEventAnalysisDto.LedgerScope> fixedScopes,RunInput input) {
        String cursor=null; Set<String> seenCursors=new HashSet<>(); int total=-1,received=0;
        do {
            MultiEventAnalysisDto.MemberPage page=data.multiEventMemberPage(claim.dataJobId(),candidate.candidateId(),cursor);
            if(page==null||!claim.dataJobId().equals(page.jobId())||!candidate.candidateId().equals(page.candidateId())
                    ||!Objects.equals(input.ledgerDigest(),page.ledgerVersionSetDigest())||!Objects.equals(cursor,page.cursor())
                    ||page.totalCount()<0||page.totalCount()>50_000||(total>=0&&total!=page.totalCount())
                    ||page.records()==null||page.records().size()>MEMBER_PAGE_SIZE)
                throw new FastApiResponseContractException("Data returned an invalid multi-event member page envelope");
            if(total<0)total=page.totalCount();
            validatePageCursor(cursor,page.nextCursor(),total,page.records().size());
            if(total!=candidate.distinctAccountCount())throw new FastApiResponseContractException("Data member total does not match its candidate");
            Set<String> local=new HashSet<>();
            for(String account:page.records())if(account==null||!account.matches("[0-9a-f]{64}")||!local.add(account))
                throw new FastApiResponseContractException("Data returned an invalid or duplicate account reference");
            if(!page.records().isEmpty()){
                validateAccountsExist(page.records(),candidate.sourceScopes(),input);
                tx.executeWithoutResult(status->{
                    requireLease(claim);
                    jdbc.batchUpdate("insert into td_multi_event_investigation_members(run_id,candidate_id,account_reference_hmac) values(?,?,?)",
                            page.records(),MEMBER_PAGE_SIZE,(statement,account)->{statement.setString(1,claim.id().toString());statement.setString(2,candidate.candidateId());statement.setString(3,account);});
                });
            }
            received+=page.records().size(); cursor=page.nextCursor();
            if(cursor!=null&&!seenCursors.add(cursor))throw new FastApiResponseContractException("Data repeated a multi-event member cursor");
        } while(cursor!=null);
        Integer stored=jdbc.queryForObject("select count(*) from td_multi_event_investigation_members where run_id=? and candidate_id=?",Integer.class,claim.id().toString(),candidate.candidateId());
        if(total<0||received!=total||stored==null||stored!=total)throw new FastApiResponseContractException("Data member pages are incomplete or duplicated");
        return stored;
    }

    private void validateAccountsExist(List<String> accounts,List<MultiEventAnalysisDto.LedgerScope> sourceScopes,RunInput input) {
        String scopesJson=write(sourceScopes.stream().map(scope->Map.of("performanceId",scope.performanceId().toString(),"ledgerVersionId",scope.ledgerVersionId().toString())).toList());
        String accountsJson=write(accounts);
        String sql="""
            with recursive selected_scopes as (
              select performance_id,ledger_version_id from json_table(?, '$[*]' columns(
                performance_id char(36) path '$.performanceId',ledger_version_id char(36) path '$.ledgerVersionId'))
            ), lineage as (
              select s.performance_id,v.id,v.base_version_id,v.generation_no,v.revision_no
              from selected_scopes s join td_bulk_ledger_versions v on v.id=s.ledger_version_id and v.status='PUBLISHED'
              union all
              select l.performance_id,p.id,p.base_version_id,p.generation_no,p.revision_no
              from lineage l join td_bulk_ledger_versions p on p.id=l.base_version_id and p.status='PUBLISHED'
                and p.generation_no=l.generation_no and p.revision_no=l.revision_no-1
            ), ranked as (
              select l.performance_id,tv.ticket_reference_hmac,tv.account_reference_hmac,tv.ticket_status,tv.purchased_at,
                     row_number() over(partition by l.performance_id,tv.ticket_reference_hmac order by l.revision_no desc) rn
              from td_bulk_ledger_ticket_versions tv join lineage l on l.id=tv.version_id
            )
            select count(*) from json_table(?, '$[*]' columns(account_reference_hmac char(64) path '$')) expected
            where exists(select 1 from ranked r where r.account_reference_hmac=expected.account_reference_hmac and r.rn=1
              and r.ticket_status='ACTIVE' and r.purchased_at>=? and r.purchased_at<?)
            """;
        Integer found=jdbc.queryForObject(sql,Integer.class,scopesJson,accountsJson,
                java.sql.Timestamp.from(input.analysisStartAt().toInstant()),java.sql.Timestamp.from(input.analysisEndAt().toInstant()));
        if(found==null||found!=accounts.size())throw new FastApiResponseContractException("Data candidate member is not present in its fixed ledger source");
    }

    private RelationshipImportSummary collectRelationships(Claim claim,MultiEventAnalysisDto.CandidateSummary candidate,
            Map<String,MultiEventAnalysisDto.LedgerScope> fixedScopes,RunInput input) {
        String cursor=null; Set<String> seenCursors=new HashSet<>(),signals=new HashSet<>(),observations=new HashSet<>(),relationshipIds=new HashSet<>();
        int total=-1,received=0; long relationshipMemberRows=0;
        do {
            MultiEventAnalysisDto.RelationshipPage page=data.multiEventRelationshipPage(claim.dataJobId(),candidate.candidateId(),cursor);
            if(page==null||!claim.dataJobId().equals(page.jobId())||!candidate.candidateId().equals(page.candidateId())
                    ||!Objects.equals(input.ledgerDigest(),page.ledgerVersionSetDigest())||!Objects.equals(cursor,page.cursor())
                    ||page.totalCount()<0||page.totalCount()>RELATIONSHIP_TYPES.size()||(total>=0&&total!=page.totalCount())
                    ||page.records()==null||page.records().size()>MEMBER_PAGE_SIZE)
                throw new FastApiResponseContractException("Data returned an invalid multi-event relationship page envelope");
            if(total<0)total=page.totalCount();
            validatePageCursor(cursor,page.nextCursor(),total,page.records().size());
            for(var relation:page.records()) {
                validateRelationshipSummary(candidate,relation,fixedScopes);
                if(!relationshipIds.add(relation.relationshipId()))
                    throw new FastApiResponseContractException("Data returned a duplicate multi-event relationship ID");
                if(!signals.add(relation.signalType()))
                    throw new FastApiResponseContractException("Data returned a duplicate multi-event relationship signal");
                Set<UUID> performances=relation.sourceScopes().stream().map(MultiEventAnalysisDto.LedgerScope::performanceId).collect(Collectors.toSet());
                if(performances.size()<2)throw new FastApiResponseContractException("Data relationship does not span distinct performances");
                for(var scope:relation.sourceScopes()) {
                    String observation=relation.signalType()+"|"+scopeKey(scope);
                    if(!observations.add(observation))throw new FastApiResponseContractException("Data repeated a relationship source");
                    boolean supported=COCLUSTER.equals(relation.signalType())
                            ? membersPresentInScope(claim,candidate,scope,input)
                            : sharedSignalExists(claim,candidate,relation.signalType(),scope,input);
                    if(!supported)
                        throw new FastApiResponseContractException("Data relationship is not supported by its fixed ledger source");
                }
                persistRelationship(claim,candidate,relation);
                relationshipMemberRows+=relation.accountCount();
                received++;
            }
            cursor=page.nextCursor();
            if(cursor!=null&&!seenCursors.add(cursor))throw new FastApiResponseContractException("Data repeated a multi-event relationship cursor");
        } while(cursor!=null);
        if(total<0||received!=total||!relationshipSignalsConsistent(signals,candidate)||observations.size()!=candidate.verifiedRelationshipCount())
            throw new FastApiResponseContractException("Data relationship pages are incomplete or inconsistent with candidate evidence");
        return new RelationshipImportSummary(observations.size(),relationshipMemberRows);
    }

    void validateRelationshipSummary(MultiEventAnalysisDto.CandidateSummary candidate,
            MultiEventAnalysisDto.RelationshipSummary relation,
            Map<String,MultiEventAnalysisDto.LedgerScope> fixedScopes) {
        if(candidate==null||relation==null||relation.relationshipId()==null||relation.relationshipId().isBlank()
                ||relation.relationshipId().length()>128
                ||!RELATIONSHIP_TYPES.contains(relation.signalType())
                ||relation.accountCount()!=candidate.distinctAccountCount()||relation.accountCount()<2
                ||relation.sourceScopeCount()<1||relation.sourceScopes()==null
                ||relation.sourceScopeCount()!=relation.sourceScopes().size()
                ||!sourceScopesValid(relation.sourceScopes(),fixedScopes,candidate.sourceScopes()))
            throw new FastApiResponseContractException("Data returned an invalid multi-event relationship summary");
        long performances=relation.sourceScopes().stream().map(MultiEventAnalysisDto.LedgerScope::performanceId).distinct().count();
        if(performances<2)throw new FastApiResponseContractException("Data relationship does not span distinct performances");
    }

    /**
     * 관계 유형은 연결 조건이고 availableSignals는 점수 구성 신호이므로 둘은 같지 않을 수 있습니다.
     * 강한 신호 반복 관계는 구성원이 그 신호를 실제로 공유했다는 뜻이므로 점수 구성 신호에 반드시 들어 있어야 합니다.
     */
    static boolean relationshipSignalsConsistent(Set<String> relationshipTypes,MultiEventAnalysisDto.CandidateSummary candidate) {
        if(relationshipTypes.isEmpty()||!RELATIONSHIP_TYPES.containsAll(relationshipTypes))return false;
        return relationshipTypes.stream().filter(STRONG_SIGNALS::contains).allMatch(candidate.availableSignals()::contains);
    }

    /** 저장된 후보 구성원 전원이 이 회차의 고정 원장에서 분석 기간 안의 ACTIVE 티켓을 가지는지 확인합니다. */
    private boolean membersPresentInScope(Claim claim,MultiEventAnalysisDto.CandidateSummary candidate,
            MultiEventAnalysisDto.LedgerScope scope,RunInput input) {
        String sql="""
            with recursive l as (
              select id,base_version_id,generation_no,revision_no from td_bulk_ledger_versions where id=? and status='PUBLISHED'
              union all select p.id,p.base_version_id,p.generation_no,p.revision_no from td_bulk_ledger_versions p join l on l.base_version_id=p.id
                where p.status='PUBLISHED' and p.generation_no=l.generation_no and p.revision_no=l.revision_no-1
            ), ranked as (
              select tv.ticket_reference_hmac,tv.account_reference_hmac,tv.ticket_status,tv.purchased_at,
                     row_number() over(partition by tv.ticket_reference_hmac order by l.revision_no desc) rn
              from td_bulk_ledger_ticket_versions tv join l on l.id=tv.version_id
            )
            select count(distinct r.account_reference_hmac) from ranked r
            join td_multi_event_investigation_members m on m.run_id=? and m.candidate_id=? and m.account_reference_hmac=r.account_reference_hmac
            where r.rn=1 and r.ticket_status='ACTIVE' and r.purchased_at>=? and r.purchased_at<?
            """;
        Integer present=jdbc.queryForObject(sql,Integer.class,scope.ledgerVersionId().toString(),claim.id().toString(),candidate.candidateId(),
                java.sql.Timestamp.from(input.analysisStartAt().toInstant()),java.sql.Timestamp.from(input.analysisEndAt().toInstant()));
        return present!=null&&present==candidate.distinctAccountCount();
    }

    /** 점수 구성 신호마다 구성원 두 명 이상이 출처 회차 하나 이상에서 실제로 공유했는지 확인합니다. */
    private void verifyAvailableSignals(Claim claim,MultiEventAnalysisDto.CandidateSummary candidate,RunInput input) {
        for(String signal:candidate.availableSignals()) {
            boolean shared=false;
            for(var scope:candidate.sourceScopes()) {
                if(signalSharedByMembers(claim,candidate,signal,scope,input)) { shared=true; break; }
            }
            if(!shared) throw new FastApiResponseContractException("Data candidate signal is not shared by its members in a fixed ledger source");
        }
    }

    private boolean signalSharedByMembers(Claim claim,MultiEventAnalysisDto.CandidateSummary candidate,String signal,
            MultiEventAnalysisDto.LedgerScope scope,RunInput input) {
        String lineage="""
            with recursive l as (
              select id,base_version_id,generation_no,revision_no from td_bulk_ledger_versions where id=? and status='PUBLISHED'
              union all select p.id,p.base_version_id,p.generation_no,p.revision_no from td_bulk_ledger_versions p join l on l.base_version_id=p.id
                where p.status='PUBLISHED' and p.generation_no=l.generation_no and p.revision_no=l.revision_no-1
            ), ranked as (
              select tv.ticket_reference_hmac,tv.account_reference_hmac,tv.ticket_status,tv.purchased_at,
                     row_number() over(partition by tv.ticket_reference_hmac order by l.revision_no desc) rn
              from td_bulk_ledger_ticket_versions tv join l on l.id=tv.version_id
            )
            """;
        String filter="""
            r.rn=1 and r.ticket_status='ACTIVE' and r.purchased_at>=? and r.purchased_at<?
              and s.hmac_purpose='TRACK1_CONNECTION' and s.hmac_key_version=(select hmac_key_version from td_multi_event_investigation_runs where id=?)
            """;
        Object[] args={scope.ledgerVersionId().toString(),scope.ledgerVersionId().toString(),claim.id().toString(),candidate.candidateId(),
                java.sql.Timestamp.from(input.analysisStartAt().toInstant()),java.sql.Timestamp.from(input.analysisEndAt().toInstant()),claim.id().toString()};
        if(signal.startsWith("ip_window:")) {
            String kind=signal.substring("ip_window:".length());
            if(!IP_NETWORK_TYPES.contains(kind))return false;
            // Data와 같이 한 시간창 안에서 망 유형이 하나면 그 유형, 섞이면 UNKNOWN으로 판정합니다.
            String sql=lineage+"""
                select count(*) from (
                  select case when count(distinct coalesce(s.ip_network_type,'UNKNOWN'))=1 then max(coalesce(s.ip_network_type,'UNKNOWN')) else 'UNKNOWN' end kind,
                         count(distinct m.account_reference_hmac) member_count
                  from ranked r
                  join td_bulk_ledger_track1_ticket_signals s on s.version_id=? and s.ticket_reference_hmac=r.ticket_reference_hmac
                  left join td_multi_event_investigation_members m on m.run_id=? and m.candidate_id=? and m.account_reference_hmac=r.account_reference_hmac
                  where s.ip_hmac is not null and """+filter+"""
                  group by s.ip_hmac,"""+IP_WINDOW_BUCKET_SQL.formatted("r.purchased_at")+"""
                ) windows where kind=? and member_count>=2
                """;
            Object[] withKind=java.util.Arrays.copyOf(args,args.length+1); withKind[args.length]=kind;
            Integer count=jdbc.queryForObject(sql,Integer.class,withKind);
            return count!=null&&count>0;
        }
        String column=switch(signal){case "refund_account_hash"->"refund_account_hmac";case "phone_hash"->"phone_hmac";case "device_id_hash"->"device_hmac";
            case "address_hash"->"address_hmac";case "payment_hash"->"payment_hmac";default->null;};
        if(column==null)return false;
        String sql=lineage+"""
            select count(*) from (
              select s.%s token from ranked r
              join td_bulk_ledger_track1_ticket_signals s on s.version_id=? and s.ticket_reference_hmac=r.ticket_reference_hmac
              join td_multi_event_investigation_members m on m.run_id=? and m.candidate_id=? and m.account_reference_hmac=r.account_reference_hmac
              where s.%s is not null and %s
              group by s.%s having count(distinct r.account_reference_hmac)>=2
            ) shared
            """.formatted(column,column,filter,column);
        Integer count=jdbc.queryForObject(sql,Integer.class,args);
        return count!=null&&count>0;
    }

    private boolean sharedSignalExists(Claim claim,MultiEventAnalysisDto.CandidateSummary candidate,String signal,
            MultiEventAnalysisDto.LedgerScope scope,RunInput input) {
        String column=switch(signal){case "refund_account_hash"->"refund_account_hmac";case "phone_hash"->"phone_hmac";case "device_id_hash"->"device_hmac";case "address_hash"->"address_hmac";default->throw new FastApiResponseContractException("Unsupported multi-event relationship signal");};
        String sql="""
            with recursive l as (
              select id,base_version_id,generation_no,revision_no from td_bulk_ledger_versions where id=? and status='PUBLISHED'
              union all select p.id,p.base_version_id,p.generation_no,p.revision_no from td_bulk_ledger_versions p join l on l.base_version_id=p.id
                where p.status='PUBLISHED' and p.generation_no=l.generation_no and p.revision_no=l.revision_no-1
            ), ranked as (
              select tv.ticket_reference_hmac,tv.account_reference_hmac,tv.ticket_status,tv.purchased_at,
                     row_number() over(partition by tv.ticket_reference_hmac order by l.revision_no desc) rn
              from td_bulk_ledger_ticket_versions tv join l on l.id=tv.version_id
            )
            select count(*) from (
              select s.%s token from ranked r
              join td_bulk_ledger_track1_ticket_signals s on s.version_id=? and s.ticket_reference_hmac=r.ticket_reference_hmac
              join td_multi_event_investigation_members m on m.run_id=? and m.candidate_id=? and m.account_reference_hmac=r.account_reference_hmac
              where r.rn=1 and r.ticket_status='ACTIVE' and r.purchased_at>=? and r.purchased_at<?
                and s.%s is not null and s.hmac_purpose='TRACK1_CONNECTION' and s.hmac_key_version=(select hmac_key_version from td_multi_event_investigation_runs where id=?)
              group by s.%s having count(distinct r.account_reference_hmac)=?
            ) supported
            """.formatted(column,column,column);
        Integer count=jdbc.queryForObject(sql,Integer.class,scope.ledgerVersionId().toString(),scope.ledgerVersionId().toString(),
                claim.id().toString(),candidate.candidateId(),java.sql.Timestamp.from(input.analysisStartAt().toInstant()),
                java.sql.Timestamp.from(input.analysisEndAt().toInstant()),claim.id().toString(),candidate.distinctAccountCount());
        return count!=null&&count>0;
    }

    private void persistRelationship(Claim claim,MultiEventAnalysisDto.CandidateSummary candidate,MultiEventAnalysisDto.RelationshipSummary relation) {
        UUID relationshipId=UUID.nameUUIDFromBytes(("MEI_RELATIONSHIP|"+claim.id()+"|"+candidate.candidateId()+"|"+relation.relationshipId()).getBytes(StandardCharsets.UTF_8));
        tx.executeWithoutResult(status->{
            requireLease(claim);
            jdbc.update("insert into td_multi_event_investigation_relationships(id,run_id,candidate_id,signal_type,account_count,source_scope_count) values(?,?,?,?,?,?)",
                    relationshipId.toString(),claim.id().toString(),candidate.candidateId(),relation.signalType(),relation.accountCount(),relation.sourceScopeCount());
            for(var source:relation.sourceScopes())jdbc.update("insert into td_multi_event_investigation_relationship_sources(relationship_id,run_id,performance_id) values(?,?,?)",
                    relationshipId.toString(),claim.id().toString(),source.performanceId().toString());
            int expected=jdbc.queryForObject("select count(*) from td_multi_event_investigation_members where run_id=? and candidate_id=?",Integer.class,claim.id().toString(),candidate.candidateId());
            int inserted=jdbc.update("insert into td_multi_event_investigation_relationship_members(relationship_id,run_id,candidate_id,account_reference_hmac) select ?,run_id,candidate_id,account_reference_hmac from td_multi_event_investigation_members where run_id=? and candidate_id=?",
                    relationshipId.toString(),claim.id().toString(),candidate.candidateId());
            if(expected!=candidate.distinctAccountCount()||inserted!=expected)throw new Blocked("RESULT_RELATIONSHIP_MEMBER_COUNT_MISMATCH");
        });
    }

    private boolean sourceScopesValid(List<MultiEventAnalysisDto.LedgerScope> scopes,
            Map<String,MultiEventAnalysisDto.LedgerScope> fixedScopes,List<MultiEventAnalysisDto.LedgerScope> subset) {
        if(scopes==null||scopes.isEmpty()||scopes.size()>500)return false;
        Set<String> allowed=subset==null?fixedScopes.keySet():subset.stream().map(MultiEventInvestigationService::scopeKey).collect(Collectors.toSet());
        Set<String> found=new HashSet<>();
        for(var scope:scopes) {
            if(scope==null)return false;
            String key;
            try{key=scopeKey(scope);}catch(RuntimeException invalid){return false;}
            if(!allowed.contains(key)||!scope.equals(fixedScopes.get(key))||!found.add(key))return false;
        }
        return found.size()==scopes.size();
    }

    private static String scopeKey(MultiEventAnalysisDto.LedgerScope scope) {
        if(scope.eventId()==null||scope.performanceId()==null||scope.ledgerVersionId()==null||scope.sourceBatchId()==null)
            throw new IllegalArgumentException("Incomplete scope");
        return scope.eventId()+"|"+scope.performanceId()+"|"+scope.ledgerVersionId()+"|"+scope.sourceBatchId();
    }

    private static void validatePageCursor(String requested,String next,int total,int pageSize) {
        int offset=0;
        if(requested!=null)try{offset=Integer.parseInt(requested);}catch(NumberFormatException e){throw new FastApiResponseContractException("Data returned a non-numeric result cursor");}
        if(offset<0||pageSize<0||offset+pageSize>total||(pageSize==0&&offset<total))
            throw new FastApiResponseContractException("Data returned an incomplete result page");
        int nextOffset=offset+pageSize;
        if(next==null) {
            if(nextOffset!=total)throw new FastApiResponseContractException("Data ended a result page sequence before its declared total");
        } else {
            try{if(Integer.parseInt(next)!=nextOffset||nextOffset>=total)throw new FastApiResponseContractException("Data returned an invalid next cursor");}
            catch(NumberFormatException e){throw new FastApiResponseContractException("Data returned a non-numeric next cursor");}
        }
    }
    private MultiEventAnalysisDto.Request request(Claim claim){
        List<Scope> scopes=scopesForWorker(claim.id()); if(scopes.size()<2)throw new Blocked("FIXED_LEDGER_SCOPE_MISSING");
        RunInput input=jdbc.query("select ledger_version_set_digest,supplier_namespace,hmac_purpose,hmac_key_version,score_version,projection_version,analysis_as_of,analysis_start_at,analysis_end_at from td_multi_event_investigation_runs where id=? and tenant_id=?",(rs,n)->new RunInput(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),time(rs,7),time(rs,8),time(rs,9)),claim.id().toString(),claim.tenantId().toString()).stream().findFirst().orElseThrow(()->new Blocked("RUN_MISSING"));
        List<MultiEventAnalysisDto.Ticket> tickets=new ArrayList<>();
        List<MultiEventAnalysisDto.CancelledTicket> cancelledTickets=new ArrayList<>();
        for(Scope scope:scopes){
            int remaining=MAX_JSON_TICKETS-tickets.size();
            List<MultiEventAnalysisDto.Ticket> scopedTickets=tickets(scope,input,remaining+1);
            tickets.addAll(scopedTickets);
            if(tickets.size()>MAX_JSON_TICKETS)throw new Blocked("MULTI_EVENT_TICKET_LIMIT_EXCEEDED");
            if(cancelledTicketTransferEnabled){
                int cancellationLimit=MAX_JSON_TICKETS-tickets.size()+1;
                List<MultiEventAnalysisDto.CancelledTicket> scopedCancelled=cancelledTickets(scope,input,
                        scopedTickets.size()+1L,cancellationLimit);
                cancelledTickets.addAll(scopedCancelled);
                if(tickets.size()+cancelledTickets.size()>MAX_JSON_TICKETS)
                    throw new Blocked("MULTI_EVENT_TICKET_LIMIT_EXCEEDED");
            }
        }
        if(tickets.isEmpty())throw new Blocked("NO_EFFECTIVE_TICKETS");
        return new MultiEventAnalysisDto.Request(claim.id(),claim.tenantId(),input.ledgerDigest(),input.analysisAsOf(),
                input.analysisStartAt(),input.analysisEndAt(),input.scoreVersion(),input.projectionVersion(),
                scopes.stream().map(s->new MultiEventAnalysisDto.LedgerScope(s.externalEventRef(),s.performanceId(),
                        s.versionId(),s.sourceBatchId(),s.policySnapshotId())).toList(),tickets,cancelledTickets);
    }
    private List<MultiEventAnalysisDto.CancelledTicket> cancelledTickets(Scope scope,RunInput input,long firstOrdinal,int limit){
        if(limit<1)return List.of();
        return jdbc.query(ARTIFACT_CANCELLED_TICKET_SQL,(rs,n)->cancelledTicketFromResultSet(rs,scope,input,firstOrdinal+n),
                scope.versionId().toString(),java.sql.Timestamp.from(input.analysisStartAt().toInstant()),
                java.sql.Timestamp.from(input.analysisEndAt().toInstant()),input.keyVersion(),limit);
    }
    private List<MultiEventAnalysisDto.Ticket> tickets(Scope scope,RunInput input,int limit){return jdbc.query("""
        with recursive l as (select id,base_version_id,generation_no,revision_no from td_bulk_ledger_versions where id=? and status='PUBLISHED'
          union all select p.id,p.base_version_id,p.generation_no,p.revision_no from td_bulk_ledger_versions p join l on l.base_version_id=p.id where p.status='PUBLISHED' and p.generation_no=l.generation_no and p.revision_no=l.revision_no-1),
        ranked as (select tv.*,row_number() over(partition by tv.ticket_reference_hmac order by l.revision_no desc) rn from td_bulk_ledger_ticket_versions tv join l on l.id=tv.version_id)
        select r.reservation_reference_hmac,r.account_reference_hmac,r.purchased_at,r.hmac_key_version,r.source_version,
               s.payment_hmac,s.address_hmac,s.device_hmac,s.ip_hmac,s.refund_account_hmac,s.phone_hmac,s.ip_network_type,s.hmac_purpose,s.hmac_key_version,s.source_version,
               ps.snapshot_id,ps.source_version,ps.ledger_complete_snapshot,ps.policy_verification_status,ps.quantity_data_status,ps.data_completeness_reason,ps.cancellation_handling,ps.duplicate_handling,ps.max_tickets_per_account,
               lf.effective_ticket_quantity,lf.max_tickets_per_account,lf.policy_version,
               r.seat_zone,r.seat_row,r.seat_number,r.reserved_at,r.paid_at,r.account_created_at
        from ranked r left join td_bulk_ledger_track1_ticket_signals s on s.version_id=? and s.ticket_reference_hmac=r.ticket_reference_hmac
        left join td_bulk_ledger_track1_policy_snapshots ps on ps.snapshot_id=? and ps.ledger_version_id=?
        left join td_bulk_ledger_track1_bulk_limit_findings lf on lf.job_id=ps.job_id and lf.performance_id=? and lf.account_reference_hmac=r.account_reference_hmac
        where r.rn=1 and r.ticket_status='ACTIVE' and r.purchased_at>=? and r.purchased_at<? order by r.ticket_reference_hmac limit ?
        """,(rs,n)->{
            String rowKey=rs.getString(4),rowSource=rs.getString(5),purpose=rs.getString(13),key=rs.getString(14),signalSource=rs.getString(15);String account=rs.getString(2),reservation=rs.getString(1);
            if(!input.keyVersion().equals(rowKey)||!input.keyVersion().equals(key)||!"TRACK1_CONNECTION".equals(purpose)||rowSource==null||!rowSource.equals(signalSource)||account==null||reservation==null||!account.matches("[0-9a-f]{64}")||!reservation.matches("[0-9a-f]{64}")
                    ||(rs.getString(6)==null&&rs.getString(7)==null&&rs.getString(8)==null&&rs.getString(9)==null&&rs.getString(10)==null&&rs.getString(11)==null))throw new Blocked("MULTI_EVENT_SIGNAL_OR_IDENTITY_INVALID");
            String quantityStatus="POLICY_NOT_CONFIRMED",quantityReason="POLICY_SNAPSHOT_UNAVAILABLE";
            Integer quantity=null,confirmedLimit=null;UUID quantitySnapshot=null;String policyVersion=null,quantitySourceVersion=null;
            String snapshotId=rs.getString(16),policySourceVersion=rs.getString(17),policyStatus=rs.getString(19),quantityDataStatus=rs.getString(20),cancellationHandling=rs.getString(22),duplicateHandling=rs.getString(23),snapshotPolicyVersion=rs.getString(27);
            long effectiveQuantity=rs.getLong(25);boolean quantityFound=!rs.wasNull();int policyLimit=rs.getInt(24);boolean policyLimitFound=!rs.wasNull();int findingLimit=rs.getInt(26);boolean findingLimitFound=!rs.wasNull();
            if(snapshotId!=null){
                quantitySnapshot=uuid(snapshotId);quantitySourceVersion=policySourceVersion;policyVersion=snapshotPolicyVersion;
                if(!"CONFIRMED".equals(policyStatus)) quantityReason="POLICY_NOT_CONFIRMED";
                else if(!"COMPLETE".equals(quantityDataStatus)||!rs.getBoolean(18)||"GROSS".equals(cancellationHandling)||!"DEDUPLICATED".equals(duplicateHandling)) {quantityStatus="INCOMPLETE";quantityReason=rs.getString(21)==null?"POLICY_QUANTITY_SOURCE_INCOMPLETE":rs.getString(21);}
                else if(policySourceVersion==null||!policySourceVersion.equals(rowSource)||!quantityFound||!policyLimitFound||!findingLimitFound||policyLimit!=findingLimit||snapshotPolicyVersion==null||!snapshotPolicyVersion.equals(rs.getString(27))||effectiveQuantity<1||effectiveQuantity>100||policyLimit<1||policyLimit>100) {quantityStatus="INCOMPLETE";quantityReason="POLICY_QUANTITY_SOURCE_MISMATCH";}
                else {quantityStatus="COMPLETE";quantityReason="POLICY_SNAPSHOT_AND_QUANTITY_VERIFIED";quantity=(int)effectiveQuantity;confirmedLimit=policyLimit;}
            }
            java.sql.Timestamp purchased=rs.getTimestamp(3);return new MultiEventAnalysisDto.Ticket(account,reservation,scope.externalEventRef(),scope.performanceId(),scope.versionId(),n+1L,input.supplierNamespace(),purpose,key,purchased==null?null:purchased.toLocalDateTime().atOffset(ZoneOffset.UTC),quantity,confirmedLimit,quantitySnapshot,policyVersion,quantitySourceVersion,rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9),rs.getString(10),rs.getString(11),rs.getString(12),quantityStatus,quantityReason,rowSource,rs.getString(28),rs.getString(29),rs.getString(30),nullableTime(rs,31),nullableTime(rs,32),nullableTime(rs,33));
        },scope.versionId().toString(),scope.versionId().toString(),scope.policySnapshotId()==null?null:scope.policySnapshotId().toString(),scope.versionId().toString(),scope.performanceId().toString(),java.sql.Timestamp.from(input.analysisStartAt().toInstant()),java.sql.Timestamp.from(input.analysisEndAt().toInstant()),limit);}
    private void complete(Claim claim,MultiEventAnalysisDto.Response response){requireLease(claim);jdbc.update("delete from td_multi_event_investigation_relationship_sources where run_id=?",claim.id().toString());jdbc.update("delete from td_multi_event_investigation_relationship_members where run_id=?",claim.id().toString());jdbc.update("delete from td_multi_event_investigation_relationships where run_id=?",claim.id().toString());jdbc.update("delete from td_multi_event_investigation_members where run_id=?",claim.id().toString());jdbc.update("delete from td_multi_event_investigation_candidates where run_id=?",claim.id().toString());for(var candidate:response.candidates()){String breakdown=write(candidate.scoreBreakdown()),available=write(candidate.availableSignals()),excluded=write(candidate.excludedSignals()),unavailable=write(candidate.unavailableSignals()),normal=write(candidate.normalSharingPossibilities());Double baseline=baselineMaxScore(claim.id(),candidate.memberAccountIds(),candidate.sourceScopes());jdbc.update("insert into td_multi_event_investigation_candidates(run_id,candidate_id,priority_band,investigation_score,baseline_max_score,distinct_account_count,distinct_event_count,distinct_performance_count,verified_relationship_count,score_breakdown_json,available_signals_json,excluded_signals_json,unavailable_signals_json,normal_sharing_json) values(?,?,?,?,?,?,?,?,?,cast(? as json),cast(? as json),cast(? as json),cast(? as json),cast(? as json))",claim.id().toString(),candidate.candidateId(),candidate.priorityBand(),candidate.scoreBreakdown().score(),baseline,candidate.distinctAccountCount(),candidate.distinctEventCount(),candidate.distinctPerformanceCount(),candidate.verifiedRelationshipCount(),breakdown,available,excluded,unavailable,normal);for(String account:candidate.memberAccountIds())jdbc.update("insert into td_multi_event_investigation_members(run_id,candidate_id,account_reference_hmac) values(?,?,?)",claim.id().toString(),candidate.candidateId(),account);for(var relation:candidate.relationships()){UUID relationship=UUID.randomUUID();jdbc.update("insert into td_multi_event_investigation_relationships(id,run_id,candidate_id,signal_type,account_count,source_scope_count) values(?,?,?,?,?,?)",relationship.toString(),claim.id().toString(),candidate.candidateId(),relation.signalType(),relation.accountIds().size(),relation.sourceScopes().size());for(String account:relation.accountIds())jdbc.update("insert into td_multi_event_investigation_relationship_members(relationship_id,run_id,candidate_id,account_reference_hmac) values(?,?,?,?)",relationship.toString(),claim.id().toString(),candidate.candidateId(),account);for(var source:relation.sourceScopes())jdbc.update("insert into td_multi_event_investigation_relationship_sources(relationship_id,run_id,performance_id) values(?,?,?)",relationship.toString(),claim.id().toString(),source.performanceId().toString());}}
        int updated=jdbc.update("update td_multi_event_investigation_runs set status='COMPLETED',failure_code=null,analysis_algorithm_version=?,lease_owner=null,lease_until=null,completed_at=utc_timestamp(6),updated_at=utc_timestamp(6),row_version=row_version+1 where id=? and status='RUNNING' and fence_token=? and lease_owner=?",response.algorithmVersion(),claim.id().toString(),claim.fence(),owner(claim));if(updated!=1)throw new Blocked("RUN_LEASE_LOST");}
    private void finish(Claim claim,String status,String code){tx.executeWithoutResult(x->{int updated=jdbc.update("update td_multi_event_investigation_runs set status=case when ?='RETRYABLE' and attempt_count<3 then 'RETRYABLE' when ?='RETRYABLE' then 'FAILED' else ? end,failure_code=?,lease_owner=null,lease_until=null,updated_at=utc_timestamp(6),row_version=row_version+1 where id=? and status='RUNNING' and fence_token=? and lease_owner=?",status,status,status,code,claim.id().toString(),claim.fence(),owner(claim));if(updated!=1)throw new Blocked("RUN_LEASE_LOST");});}
    private void requireLease(Claim claim){Integer count=jdbc.queryForObject("select count(*) from td_multi_event_investigation_runs where id=? and status='RUNNING' and fence_token=? and lease_owner=?",Integer.class,claim.id().toString(),claim.fence(),owner(claim));if(count==null||count!=1)throw new Blocked("RUN_LEASE_LOST");}
    Double baselineMaxScore(UUID runId,List<String> memberAccountIds,List<MultiEventAnalysisDto.LedgerScope> sourceScopes){List<String> versions=sourceScopes.stream().map(scope->scope.ledgerVersionId().toString()).distinct().toList();if(versions.isEmpty()||memberAccountIds==null||memberAccountIds.isEmpty())return null;String marks=String.join(",",java.util.Collections.nCopies(versions.size(),"?"));List<Object> args=new ArrayList<>();args.add(write(memberAccountIds));args.addAll(versions);args.add(memberAccountIds.size());args.add(runId.toString());args.add(runId.toString());args.add(memberAccountIds.size());String sql="select max(exact_cluster.risk_score) from (select c.risk_score from td_bulk_ledger_track1_bulk_jobs j join td_bulk_ledger_track1_bulk_clusters c on c.job_id=j.id join td_bulk_ledger_track1_bulk_members m on m.job_id=c.job_id and m.cluster_id=c.cluster_id join json_table(?, '$[*]' columns(account_reference_hmac char(64) path '$')) expected on expected.account_reference_hmac=m.account_reference_hmac where j.ledger_version_id in ("+marks+") and j.status='COMPLETED' and j.algorithm_version like '%score-v2-exp1' and c.member_count=? and j.tenant_id=(select r.tenant_id from td_multi_event_investigation_runs r where r.id=?) and j.hmac_key_version=(select r.hmac_key_version from td_multi_event_investigation_runs r where r.id=?) group by j.id,c.cluster_id,c.risk_score having count(distinct m.account_reference_hmac)=?) exact_cluster";
        return jdbc.query(sql,rs->rs.next()?rs.getObject(1,Double.class):null,args.toArray());}
    private Double baselineMaxScore(UUID runId,String candidateId,int memberCount,List<MultiEventAnalysisDto.LedgerScope> sourceScopes){
        List<String> versions=sourceScopes.stream().map(scope->scope.ledgerVersionId().toString()).distinct().toList();
        if(versions.isEmpty()||candidateId==null||memberCount<2)return null;
        String marks=String.join(",",java.util.Collections.nCopies(versions.size(),"?"));
        List<Object> args=new ArrayList<>();
        args.add(runId.toString());args.add(candidateId);args.addAll(versions);args.add(memberCount);args.add(runId.toString());args.add(runId.toString());args.add(memberCount);
        String sql="select max(exact_cluster.risk_score) from (select c.risk_score from td_bulk_ledger_track1_bulk_jobs j join td_bulk_ledger_track1_bulk_clusters c on c.job_id=j.id join td_bulk_ledger_track1_bulk_members m on m.job_id=c.job_id and m.cluster_id=c.cluster_id join td_multi_event_investigation_members expected on expected.run_id=? and expected.candidate_id=? and expected.account_reference_hmac=m.account_reference_hmac where j.ledger_version_id in ("+marks+") and j.status='COMPLETED' and j.algorithm_version like '%score-v2-exp1' and c.member_count=? and j.tenant_id=(select r.tenant_id from td_multi_event_investigation_runs r where r.id=?) and j.hmac_key_version=(select r.hmac_key_version from td_multi_event_investigation_runs r where r.id=?) group by j.id,c.cluster_id,c.risk_score having count(distinct m.account_reference_hmac)=?) exact_cluster";
        return jdbc.query(sql,rs->rs.next()?rs.getObject(1,Double.class):null,args.toArray());
    }
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new Blocked("RESULT_SERIALIZATION_FAILED");}}
    private static String owner(Claim claim){return "mei-"+claim.id();}
    private static void validPage(int page,int size){if(page<0||size<1||size>100)throw invalid();}
    private static <T> Page<T> page(List<T> rows,int page,int size){boolean next=rows.size()>size;if(next)rows=rows.subList(0,size);return new Page<>(rows,page,size,next);}
    private static UUID uuid(String value){return UUID.fromString(value);} private static OffsetDateTime time(java.sql.ResultSet rs,int index)throws java.sql.SQLException{return rs.getTimestamp(index).toLocalDateTime().atOffset(ZoneOffset.UTC);}private static OffsetDateTime nullableTime(java.sql.ResultSet rs,int index)throws java.sql.SQLException{return rs.getTimestamp(index)==null?null:time(rs,index);}
    private static Long nullableLong(java.sql.ResultSet rs,int index)throws java.sql.SQLException{long value=rs.getLong(index);return rs.wasNull()?null:value;}
    private static CaseApiException invalid(){return new CaseApiException(HttpStatus.BAD_REQUEST,"INVALID_MULTI_EVENT_INVESTIGATION_REQUEST");} private static CaseApiException incomplete(String code){return new CaseApiException(HttpStatus.CONFLICT,code);}private static CaseApiException notFound(){return new CaseApiException(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND");}
    public record PerformanceSelection(UUID eventId,List<UUID> performanceIds){}
    public record CreateRequest(List<UUID> eventIds,List<PerformanceSelection> performanceSelections,OffsetDateTime analysisStartAt,OffsetDateTime analysisEndAt){}
    public record Accepted(UUID runId,boolean existing,String status){}
    public record Run(UUID id,String status,String failureCode,String ledgerVersionSetDigest,String scoreVersion,String projectionVersion,String algorithmVersion,OffsetDateTime analysisAsOf,OffsetDateTime analysisStartAt,OffsetDateTime analysisEndAt,OffsetDateTime createdAt,OffsetDateTime updatedAt,OffsetDateTime completedAt){}
    public record Scope(UUID eventId,UUID performanceId,UUID versionId,String externalEventRef,String sourceBatchId,String supplierNamespace,UUID policySnapshotId){}
    record IdentityContract(String purpose,String keyVersion){}
    private record VersionIdentity(UUID versionId,String purpose,String keyVersion){}
    private record Existing(UUID id,String requestDigest,String status){}
    private record ArtifactWriteResult(long recordCount,int cancelledTicketRecordCount){}
    private record CandidateBudget(int memberCount,long relationshipMemberRows){}
    private record RelationshipImportSummary(int observations,long memberRows){}
    record Claim(UUID id,UUID tenantId,long fence,String priorStatus,UUID dataJobId,String artifactId,
                         String objectVersion,String artifactSha256,Long artifactRecordCount,
                         int artifactCancelledTicketCount,int attemptCount){}
    private record RunInput(String ledgerDigest,String supplierNamespace,String purpose,String keyVersion,String scoreVersion,String projectionVersion,OffsetDateTime analysisAsOf,OffsetDateTime analysisStartAt,OffsetDateTime analysisEndAt){}
    private static final class Blocked extends RuntimeException{private final String code;private Blocked(String code){this.code=code;}}
    public record ScopeView(UUID eventId,UUID performanceId,UUID ledgerVersionId,String eventReference,String sourceBatchId){}
    public record Candidate(String candidateId,String priorityBand,double investigationScore,Double baselineMaxScore,int distinctAccountCount,int distinctEventCount,int distinctPerformanceCount,int verifiedRelationshipCount,String scoreBreakdown,String availableSignals,String excludedSignals,String unavailableSignals,String normalSharingPossibilities){}
    public record Member(String memberId){}
    public record Relationship(UUID relationshipId,String signalType,int accountCount,int sourceScopeCount,List<String> memberIds,boolean membersTruncated,List<ScopeView> sourceScopes){}
    public record Page<T>(List<T> items,int page,int pageSize,boolean hasNext){}
}
