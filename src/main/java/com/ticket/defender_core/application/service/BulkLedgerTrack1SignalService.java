package com.ticket.defender_core.application.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Explicit synthetic-only connection tokens for a published ledger version; no reference token is repurposed. */
@Service
public class BulkLedgerTrack1SignalService {
    private static final Set<com.ticket.defender_core.security.AdminRole> ROLES = Set.of(com.ticket.defender_core.security.AdminRole.TENANT_ADMIN);
    private final CandidatePipelineAccess access; private final SyntheticReservationImportGate gate; private final JdbcTemplate jdbc;
    public BulkLedgerTrack1SignalService(CandidatePipelineAccess access, SyntheticReservationImportGate gate, JdbcTemplate jdbc) { this.access=access; this.gate=gate; this.jdbc=jdbc; }
    @Transactional
    public void register(UUID eventId, UUID versionId, List<Signal> values) {
        CandidatePipelineAccess.Scope scope=access.event(eventId, ROLES); gate.requireSyntheticScope(scope.tenantId(), java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        if(values==null||values.isEmpty()) throw invalid();
        for(Signal v:values) {
            if(v==null||!token(v.ticketReferenceHmac())||!token(v.paymentHmac())&&!token(v.addressHmac())&&!token(v.deviceHmac())&&!token(v.ipHmac())||blank(v.hmacKeyVersion())||blank(v.sourceVersion())) throw invalid();
            Integer ticket=jdbc.queryForObject("""
                    select count(*) from td_bulk_ledger_ticket_versions tv join td_bulk_ledger_versions v on v.id=tv.version_id
                    where tv.version_id=? and tv.ticket_reference_hmac=? and tv.hmac_key_version=? and v.tenant_id=? and v.event_id=? and v.status='PUBLISHED'
                    """,Integer.class,versionId.toString(),v.ticketReferenceHmac(),v.hmacKeyVersion(),scope.tenantId().toString(),scope.eventId().toString());
            if(ticket==null||ticket!=1) throw notFound();
            List<Signal> existing=jdbc.query("select ticket_reference_hmac,hmac_key_version,payment_hmac,address_hmac,device_hmac,ip_hmac,source_version from td_bulk_ledger_track1_ticket_signals where version_id=? and ticket_reference_hmac=?",
                    (rs,n)->new Signal(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7)),versionId.toString(),v.ticketReferenceHmac());
            if(!existing.isEmpty()) { if(existing.getFirst().equals(v)) continue; throw new CaseApiException(HttpStatus.CONFLICT,"LEDGER_SIGNAL_ALREADY_RECORDED"); }
            jdbc.update("insert into td_bulk_ledger_track1_ticket_signals(id,version_id,ticket_reference_hmac,hmac_key_version,hmac_purpose,payment_hmac,address_hmac,device_hmac,ip_hmac,source_version,created_at) values(?,?,?,?, 'TRACK1_CONNECTION',?,?,?,?,?,utc_timestamp(6))",
                    UUID.randomUUID().toString(),versionId.toString(),v.ticketReferenceHmac(),v.hmacKeyVersion(),nullable(v.paymentHmac()),nullable(v.addressHmac()),nullable(v.deviceHmac()),nullable(v.ipHmac()),v.sourceVersion());
        }
    }
    public record Signal(String ticketReferenceHmac,String hmacKeyVersion,String paymentHmac,String addressHmac,String deviceHmac,String ipHmac,String sourceVersion){}
    private static boolean token(String s){return s!=null&&s.matches("[0-9a-f]{64}");} private static String nullable(String s){return s==null?null:s;}
    private static boolean blank(String s){return s==null||s.isBlank()||s.length()>128;} private static CaseApiException invalid(){return new CaseApiException(HttpStatus.BAD_REQUEST,"INVALID_LEDGER_SIGNAL");} private static CaseApiException notFound(){return new CaseApiException(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND");}
}
