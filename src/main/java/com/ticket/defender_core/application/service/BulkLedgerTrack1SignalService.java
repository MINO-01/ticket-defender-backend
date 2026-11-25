package com.ticket.defender_core.application.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 공개 원장 버전의 명시적 합성 연결 토큰만 저장하며 참조 토큰을 재사용하지 않습니다. */
@Service
public class BulkLedgerTrack1SignalService {
    private static final Set<com.ticket.defender_core.security.AdminRole> ROLES = Set.of(com.ticket.defender_core.security.AdminRole.TENANT_ADMIN);
    private final CandidatePipelineAccess access; private final SyntheticReservationImportGate gate; private final JdbcTemplate jdbc;
    public BulkLedgerTrack1SignalService(CandidatePipelineAccess access, SyntheticReservationImportGate gate, JdbcTemplate jdbc) { this.access=access; this.gate=gate; this.jdbc=jdbc; }
    @Transactional
    public void register(UUID eventId, UUID versionId, List<Signal> values) {
        CandidatePipelineAccess.Scope scope=access.event(eventId, ROLES); gate.requireSyntheticScope(scope.tenantId(), java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        if(values==null||values.isEmpty()) throw invalid();
        for(Signal input:values) {
            if(input==null||!token(input.ticketReferenceHmac())||!token(input.paymentHmac())&&!token(input.addressHmac())&&!token(input.deviceHmac())&&!token(input.ipHmac())&&!token(input.refundAccountHmac())&&!token(input.phoneHmac())||blank(input.hmacKeyVersion())||blank(input.sourceVersion())) throw invalid();
            if(input.ipNetworkType()!=null&&!token(input.ipHmac())) throw invalid();
            Signal v=input.normalized();
            if(v.ipNetworkType()!=null&&(!token(v.ipHmac())||!network(v.ipNetworkType()))) throw invalid();
            Integer ticket=jdbc.queryForObject("""
                    with recursive lineage as (
                      select id,base_version_id,generation_no,revision_no from td_bulk_ledger_versions where id=? and tenant_id=? and event_id=? and status='PUBLISHED'
                      union all select parent.id,parent.base_version_id,parent.generation_no,parent.revision_no
                        from td_bulk_ledger_versions parent join lineage child on child.base_version_id=parent.id
                        where parent.status='PUBLISHED' and parent.generation_no=child.generation_no and parent.revision_no=child.revision_no-1
                    ), ranked as (
                      select tv.ticket_reference_hmac,tv.hmac_key_version,tv.source_version,row_number() over(partition by tv.ticket_reference_hmac order by l.revision_no desc) rn
                      from td_bulk_ledger_ticket_versions tv join lineage l on l.id=tv.version_id
                    ) select count(*) from ranked where rn=1 and ticket_reference_hmac=? and hmac_key_version=? and source_version=?
                    """,Integer.class,versionId.toString(),scope.tenantId().toString(),scope.eventId().toString(),v.ticketReferenceHmac(),v.hmacKeyVersion(),v.sourceVersion());
            if(ticket==null||ticket!=1) throw notFound();
            List<Signal> existing=jdbc.query("select ticket_reference_hmac,hmac_key_version,payment_hmac,address_hmac,device_hmac,ip_hmac,source_version,refund_account_hmac,phone_hmac,ip_network_type from td_bulk_ledger_track1_ticket_signals where version_id=? and ticket_reference_hmac=?",
                    (rs,n)->new Signal(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9),rs.getString(10)),versionId.toString(),v.ticketReferenceHmac());
            if(!existing.isEmpty()) { if(existing.getFirst().equals(v)) continue; throw new CaseApiException(HttpStatus.CONFLICT,"LEDGER_SIGNAL_ALREADY_RECORDED"); }
            jdbc.update("insert into td_bulk_ledger_track1_ticket_signals(id,version_id,ticket_reference_hmac,hmac_key_version,hmac_purpose,payment_hmac,address_hmac,device_hmac,ip_hmac,refund_account_hmac,phone_hmac,ip_network_type,source_version,created_at) values(?,?,?,?, 'TRACK1_CONNECTION',?,?,?,?,?,?,?,?,utc_timestamp(6))",
                    UUID.randomUUID().toString(),versionId.toString(),v.ticketReferenceHmac(),v.hmacKeyVersion(),nullable(v.paymentHmac()),nullable(v.addressHmac()),nullable(v.deviceHmac()),nullable(v.ipHmac()),nullable(v.refundAccountHmac()),nullable(v.phoneHmac()),nullable(v.ipNetworkType()),v.sourceVersion());
        }
    }
    public record Signal(String ticketReferenceHmac,String hmacKeyVersion,String paymentHmac,String addressHmac,String deviceHmac,String ipHmac,String sourceVersion,String refundAccountHmac,String phoneHmac,String ipNetworkType){
        public Signal(String ticketReferenceHmac,String hmacKeyVersion,String paymentHmac,String addressHmac,String deviceHmac,String ipHmac,String sourceVersion){this(ticketReferenceHmac,hmacKeyVersion,paymentHmac,addressHmac,deviceHmac,ipHmac,sourceVersion,null,null,null);}
        Signal normalized(){return new Signal(ticketReferenceHmac,hmacKeyVersion,paymentHmac,addressHmac,deviceHmac,ipHmac,sourceVersion,refundAccountHmac,phoneHmac,ipHmac==null?null:(ipNetworkType==null?"UNKNOWN":ipNetworkType));}
    }
    private static boolean token(String s){return s!=null&&s.matches("[0-9a-f]{64}");} private static String nullable(String s){return s==null?null:s;}
    private static boolean blank(String s){return s==null||s.isBlank()||s.length()>128;} private static boolean network(String s){return Set.of("MOBILE_CGNAT","RESIDENTIAL","CORPORATE_EDU","DATACENTER_PROXY","UNKNOWN").contains(s);} private static CaseApiException invalid(){return new CaseApiException(HttpStatus.BAD_REQUEST,"INVALID_LEDGER_SIGNAL");} private static CaseApiException notFound(){return new CaseApiException(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND");}
}
