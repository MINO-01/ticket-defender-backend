package com.ticket.defender_core.application.service;

import com.ticket.defender_core.security.AdminRole;
import com.ticket.defender_core.security.AdminAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 권한이 확인된 조사 범위에서만 암호화 참조번호를 조회합니다. 원본 값은 audit이나 로그에 쓰지 않습니다. */
@Service
public class InvestigationTargetService {
    private static final Set<AdminRole> REVEAL_ROLES = Set.of(AdminRole.TENANT_ADMIN, AdminRole.ANALYST);
    private static final int PAGE_MAX = 100;
    private static final int EXPORT_MAX = 500;
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private final CandidatePipelineAccess access;
    private final AdminAccessService adminAccess;
    private final JdbcTemplate jdbc;
    private final InvestigationReferenceCipher cipher;

    public InvestigationTargetService(CandidatePipelineAccess access, AdminAccessService adminAccess,
            JdbcTemplate jdbc, InvestigationReferenceCipher cipher) {
        this.access = access; this.adminAccess = adminAccess; this.jdbc = jdbc; this.cipher = cipher;
    }

    @Transactional
    public TargetPage multiEventTargets(UUID eventId, UUID runId, String candidateId, int page, int pageSize, String reason) {
        String checkedReason = reason(reason);
        validatePage(page, pageSize);
        var scope = requireEvent(eventId);
        cipher.requireAvailableForTenant(scope.tenantId());
        requireMultiEventRun(scope, runId, candidateId);
        int total = Math.toIntExact(multiEventTargetCount(scope.tenantId(), runId, candidateId));
        List<StoredTarget> pageRows = multiEventRows(scope.tenantId(), runId, candidateId, pageSize + 1, (long) page * pageSize);
        boolean hasNext = pageRows.size() > pageSize;
        List<RevealedTarget> decoded = trimLookahead(pageRows, pageSize).stream().map(this::reveal).toList();
        List<Target> values = decoded.stream().map(InvestigationTargetService::masked).toList();
        audit(scope.tenantId(), eventId, scopeSubject(), "MULTI_EVENT", candidateId, "TARGETS", values.size(), redactReason(checkedReason, decoded));
        return new TargetPage(values, page, pageSize, total, hasNext);
    }

    @Transactional
    public RevealedPage revealMultiEvent(UUID eventId, UUID runId, String candidateId, int page, int pageSize, String reason) {
        String checkedReason = reason(reason);
        validatePage(page, pageSize);
        var scope = requireEvent(eventId);
        cipher.requireAvailableForTenant(scope.tenantId());
        requireMultiEventRun(scope, runId, candidateId);
        int total = Math.toIntExact(multiEventTargetCount(scope.tenantId(), runId, candidateId));
        List<StoredTarget> pageRows = multiEventRows(scope.tenantId(), runId, candidateId, pageSize + 1, (long) page * pageSize);
        boolean hasNext = pageRows.size() > pageSize;
        List<RevealedTarget> values = trimLookahead(pageRows, pageSize).stream().map(this::reveal).toList();
        audit(scope.tenantId(), eventId, scopeSubject(), "MULTI_EVENT", candidateId, "REVEAL", values.size(), redactReason(checkedReason, values));
        return new RevealedPage(values, page, pageSize, total, hasNext);
    }

    @Transactional
    public byte[] exportMultiEvent(UUID eventId, UUID runId, String candidateId, String reason) {
        String checkedReason = reason(reason);
        var scope = requireEvent(eventId);
        cipher.requireAvailableForTenant(scope.tenantId());
        requireMultiEventRun(scope, runId, candidateId);
        List<StoredTarget> rows = multiEventRows(scope.tenantId(), runId, candidateId, EXPORT_MAX + 1, 0);
        if (rows.size() > EXPORT_MAX) throw tooMany();
        List<RevealedTarget> revealed = rows.stream().map(this::reveal).toList();
        audit(scope.tenantId(), eventId, scopeSubject(), "MULTI_EVENT", candidateId, "EXPORT", revealed.size(), redactReason(checkedReason, revealed));
        return csv(revealed);
    }

    @Transactional
    public TargetPage bulkClusterTargets(UUID eventId, UUID performanceId, UUID jobId, String clusterId,
            int page, int pageSize, String reason) {
        String checkedReason = reason(reason);
        validatePage(page, pageSize);
        var scope = requireEvent(eventId);
        cipher.requireAvailableForTenant(scope.tenantId());
        requireBulkCluster(scope.tenantId(), eventId, performanceId, jobId, clusterId);
        int total = Math.toIntExact(bulkTargetCount(scope.tenantId(), eventId, performanceId, jobId, clusterId));
        List<StoredTarget> pageRows = bulkRows(scope.tenantId(), eventId, performanceId, jobId, clusterId, pageSize + 1, (long) page * pageSize);
        boolean hasNext = pageRows.size() > pageSize;
        List<RevealedTarget> decoded = trimLookahead(pageRows, pageSize).stream().map(this::reveal).toList();
        List<Target> values = decoded.stream().map(InvestigationTargetService::masked).toList();
        audit(scope.tenantId(), eventId, scopeSubject(), "BULK_CLUSTER", clusterId, "TARGETS", values.size(), redactReason(checkedReason, decoded));
        return new TargetPage(values, page, pageSize, total, hasNext);
    }

    @Transactional
    public RevealedPage revealBulkCluster(UUID eventId, UUID performanceId, UUID jobId, String clusterId,
            int page, int pageSize, String reason) {
        String checkedReason = reason(reason);
        validatePage(page, pageSize);
        var scope = requireEvent(eventId);
        cipher.requireAvailableForTenant(scope.tenantId());
        requireBulkCluster(scope.tenantId(), eventId, performanceId, jobId, clusterId);
        int total = Math.toIntExact(bulkTargetCount(scope.tenantId(), eventId, performanceId, jobId, clusterId));
        List<StoredTarget> pageRows = bulkRows(scope.tenantId(), eventId, performanceId, jobId, clusterId, pageSize + 1, (long) page * pageSize);
        boolean hasNext = pageRows.size() > pageSize;
        List<RevealedTarget> values = trimLookahead(pageRows, pageSize).stream().map(this::reveal).toList();
        audit(scope.tenantId(), eventId, scopeSubject(), "BULK_CLUSTER", clusterId, "REVEAL", values.size(), redactReason(checkedReason, values));
        return new RevealedPage(values, page, pageSize, total, hasNext);
    }

    @Transactional
    public byte[] exportBulkCluster(UUID eventId, UUID performanceId, UUID jobId, String clusterId, String reason) {
        String checkedReason = reason(reason);
        var scope = requireEvent(eventId);
        cipher.requireAvailableForTenant(scope.tenantId());
        requireBulkCluster(scope.tenantId(), eventId, performanceId, jobId, clusterId);
        List<StoredTarget> rows = bulkRows(scope.tenantId(), eventId, performanceId, jobId, clusterId, EXPORT_MAX + 1, 0);
        if (rows.size() > EXPORT_MAX) throw tooMany();
        List<RevealedTarget> revealed = rows.stream().map(this::reveal).toList();
        audit(scope.tenantId(), eventId, scopeSubject(), "BULK_CLUSTER", clusterId, "EXPORT", revealed.size(), redactReason(checkedReason, revealed));
        return csv(revealed);
    }

    @Transactional(readOnly = true)
    public List<AuditEntry> audit(UUID eventId) {
        var scope = requireEvent(eventId);
        return jdbc.query("select actor_subject,target_type,resource_id,action,target_count,reason,accessed_at from td_investigation_reference_access_audit where tenant_id=? and event_id=? order by accessed_at desc limit 100",
                (rs, n) -> new AuditEntry(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5),
                        rs.getString(6), rs.getTimestamp(7).toLocalDateTime().atOffset(ZoneOffset.UTC)), scope.tenantId().toString(), eventId.toString());
    }

    private CandidatePipelineAccess.Scope requireEvent(UUID eventId) {
        try { return access.event(eventId, REVEAL_ROLES); }
        catch (AccessDeniedException denied) { throw notFound(); }
    }

    private String scopeSubject() {
        return adminAccess.authenticatedSubject();
    }

    private void requireMultiEventRun(CandidatePipelineAccess.Scope scope, UUID runId, String candidateId) {
        if (candidateId == null || !candidateId.matches("multi-[a-f0-9]{64}")) throw badRequest("INVALID_CANDIDATE_ID");
        Integer exists = jdbc.queryForObject("select count(*) from td_multi_event_investigation_runs where id=? and tenant_id=? and status='COMPLETED' and exists (select 1 from td_multi_event_investigation_scopes s where s.run_id=td_multi_event_investigation_runs.id and s.event_id=?)",
                Integer.class, runId.toString(), scope.tenantId().toString(), scope.eventId().toString());
        if (exists == null || exists != 1) throw notFound();
        Integer candidate = jdbc.queryForObject("select count(*) from td_multi_event_investigation_candidates where run_id=? and candidate_id=?",
                Integer.class, runId.toString(), candidateId);
        if (candidate == null || candidate != 1) throw notFound();
        List<UUID> events = jdbc.query("select distinct event_id from td_multi_event_investigation_scopes where run_id=? and tenant_id=? order by event_id",
                (rs, n) -> UUID.fromString(rs.getString(1)), runId.toString(), scope.tenantId().toString());
        if (events.isEmpty() || !events.contains(scope.eventId())) throw notFound();
        for (UUID event : events) {
            CandidatePipelineAccess.Scope current = requireEvent(event);
            if (!scope.tenantId().equals(current.tenantId()) || !scope.membershipId().equals(current.membershipId())) throw notFound();
        }
    }

    private void requireBulkCluster(UUID tenantId, UUID eventId, UUID performanceId, UUID jobId, String clusterId) {
        Integer found = jdbc.queryForObject("select count(*) from td_bulk_ledger_track1_bulk_jobs j join td_bulk_ledger_track1_bulk_clusters c on c.job_id=j.id where j.id=? and j.tenant_id=? and j.event_id=? and j.performance_id=? and j.status='COMPLETED' and c.cluster_id=?",
                Integer.class, jobId.toString(), tenantId.toString(), eventId.toString(), performanceId.toString(), clusterId);
        if (found == null || found != 1) throw notFound();
    }

    private List<StoredTarget> multiEventRows(UUID tenantId, UUID runId, String candidateId, int limit, long offset) {
        return jdbc.query("""
                select c.id,c.import_id,c.tenant_id,c.event_id,c.performance_id,c.source_row_no,c.ticket_reference_hmac,
                  c.reservation_reference_hmac,c.account_reference_hmac,c.reference_ciphertext,c.nonce,c.encryption_key_version,
                  t.purchased_at,t.ticket_status,t.seat_zone,t.seat_row,t.seat_number,t.reserved_at,mr.reasons_json
                from td_multi_event_investigation_scopes s
                join td_multi_event_investigation_members m on m.run_id=s.run_id and m.candidate_id=?
                left join td_multi_event_investigation_member_reasons mr on mr.run_id=m.run_id
                  and mr.candidate_id=m.candidate_id and mr.account_reference_hmac=m.account_reference_hmac
                join td_investigation_reference_ciphertexts c on c.ledger_version_id=s.ledger_version_id
                  and c.tenant_id=s.tenant_id and c.event_id=s.event_id and c.performance_id=s.performance_id
                  and c.account_reference_hmac=m.account_reference_hmac
                join td_bulk_ledger_ticket_versions t on t.version_id=c.ledger_version_id and t.ticket_reference_hmac=c.ticket_reference_hmac
                where s.run_id=? and s.tenant_id=?
                order by c.event_id,c.performance_id,c.source_row_no limit ? offset ?
                """, (rs, n) -> stored(rs), candidateId, runId.toString(), tenantId.toString(), limit, offset);
    }

    private long multiEventTargetCount(UUID tenantId, UUID runId, String candidateId) {
        Long count = jdbc.queryForObject("""
                select count(*) from td_multi_event_investigation_scopes s
                join td_multi_event_investigation_members m on m.run_id=s.run_id and m.candidate_id=?
                join td_investigation_reference_ciphertexts c on c.ledger_version_id=s.ledger_version_id
                  and c.tenant_id=s.tenant_id and c.event_id=s.event_id and c.performance_id=s.performance_id
                  and c.account_reference_hmac=m.account_reference_hmac
                join td_bulk_ledger_ticket_versions t on t.version_id=c.ledger_version_id and t.ticket_reference_hmac=c.ticket_reference_hmac
                where s.run_id=? and s.tenant_id=?
                """, Long.class, candidateId, runId.toString(), tenantId.toString());
        return count == null ? 0 : count;
    }

    private List<StoredTarget> bulkRows(UUID tenantId, UUID eventId, UUID performanceId, UUID jobId, String clusterId, int limit, long offset) {
        return jdbc.query("""
                select c.id,c.import_id,c.tenant_id,c.event_id,c.performance_id,c.source_row_no,c.ticket_reference_hmac,
                  c.reservation_reference_hmac,c.account_reference_hmac,c.reference_ciphertext,c.nonce,c.encryption_key_version,
                  t.purchased_at,t.ticket_status,t.seat_zone,t.seat_row,t.seat_number,t.reserved_at,null
                from td_bulk_ledger_track1_bulk_jobs j
                join td_bulk_ledger_track1_bulk_members m on m.job_id=j.id and m.cluster_id=?
                join td_investigation_reference_ciphertexts c on c.ledger_version_id=j.ledger_version_id
                  and c.tenant_id=j.tenant_id and c.event_id=j.event_id and c.performance_id=j.performance_id
                  and c.account_reference_hmac=m.account_reference_hmac and c.reservation_reference_hmac=m.reservation_reference_hmac
                join td_bulk_ledger_ticket_versions t on t.version_id=c.ledger_version_id and t.ticket_reference_hmac=c.ticket_reference_hmac
                where j.id=? and j.tenant_id=? and j.event_id=? and j.performance_id=? and j.status='COMPLETED'
                order by c.source_row_no limit ? offset ?
                """, (rs, n) -> stored(rs), clusterId, jobId.toString(), tenantId.toString(), eventId.toString(), performanceId.toString(), limit, offset);
    }

    private long bulkTargetCount(UUID tenantId, UUID eventId, UUID performanceId, UUID jobId, String clusterId) {
        Long count = jdbc.queryForObject("""
                select count(*) from td_bulk_ledger_track1_bulk_jobs j
                join td_bulk_ledger_track1_bulk_members m on m.job_id=j.id and m.cluster_id=?
                join td_investigation_reference_ciphertexts c on c.ledger_version_id=j.ledger_version_id
                  and c.tenant_id=j.tenant_id and c.event_id=j.event_id and c.performance_id=j.performance_id
                  and c.account_reference_hmac=m.account_reference_hmac and c.reservation_reference_hmac=m.reservation_reference_hmac
                join td_bulk_ledger_ticket_versions t on t.version_id=c.ledger_version_id and t.ticket_reference_hmac=c.ticket_reference_hmac
                where j.id=? and j.tenant_id=? and j.event_id=? and j.performance_id=? and j.status='COMPLETED'
                """, Long.class, clusterId, jobId.toString(), tenantId.toString(), eventId.toString(), performanceId.toString());
        return count == null ? 0 : count;
    }

    private StoredTarget stored(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new StoredTarget(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)), UUID.fromString(rs.getString(3)),
                UUID.fromString(rs.getString(4)), UUID.fromString(rs.getString(5)), rs.getLong(6), rs.getString(7), rs.getString(8),
                rs.getString(9), rs.getBytes(10), rs.getBytes(11), rs.getString(12), rs.getTimestamp(13).toLocalDateTime(),
                rs.getString(14), rs.getString(15), rs.getString(16), rs.getString(17),
                rs.getTimestamp(18) == null ? null : rs.getTimestamp(18).toLocalDateTime(), reasonCodes(rs.getString(19)));
    }

    private RevealedTarget reveal(StoredTarget row) {
        var refs = cipher.decrypt(row.importId(), row.tenantId(), row.eventId(), row.performanceId(), row.rowNumber(),
                row.ticketHmac(), row.reservationHmac(), row.accountHmac(),
                new InvestigationReferenceCipher.Encrypted(row.keyVersion(), row.nonce(), row.ciphertext()));
        return new RevealedTarget(row.id().toString(), refs.accountReference(), refs.reservationReference(), refs.ticketReference(),
                row.performanceId(), row.purchasedAt(), row.ticketStatus(), row.seatZone(), row.seatRow(), row.seatNumber(),
                row.reasons(), row.reservedAt());
    }

    static List<String> reasonCodes(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode root = JSON.readTree(json);
            if (root == null || !root.isArray()) throw new IllegalStateException("Stored member evidence is invalid");
            java.util.ArrayList<String> codes = new java.util.ArrayList<>();
            for (JsonNode item : root) {
                JsonNode code = item == null ? null : item.get("code");
                if (code == null || !code.isString() || code.asString().isBlank())
                    throw new IllegalStateException("Stored member evidence is invalid");
                codes.add(code.asString());
            }
            return List.copyOf(codes);
        } catch (RuntimeException malformed) {
            if (malformed instanceof IllegalStateException invalid) throw invalid;
            throw new IllegalStateException("Stored member evidence is invalid", malformed);
        }
    }

    private static List<StoredTarget> trimLookahead(List<StoredTarget> rows, int pageSize) {
        return rows.size() > pageSize ? List.copyOf(rows.subList(0, pageSize)) : List.copyOf(rows);
    }

    private void audit(UUID tenantId, UUID eventId, String subject, String type, String resource, String action, int count, String reason) {
        jdbc.update("insert into td_investigation_reference_access_audit(id,tenant_id,event_id,actor_subject,target_type,resource_id,action,target_count,reason,accessed_at) values(?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), tenantId.toString(), eventId.toString(), subject, type, resource, action, count,
                reason, Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC)));
    }

    private static String reason(String value) {
        if (value == null || value.strip().isEmpty() || value.strip().length() > 500
                || value.codePoints().anyMatch(Character::isISOControl)) throw badRequest("INVESTIGATION_REASON_REQUIRED");
        return value.strip().replaceAll("(?i)(?<![a-f0-9])[a-f0-9]{64}(?![a-f0-9])", "[redacted]");
    }

    private static String redactReason(String reason, List<RevealedTarget> rows) {
        String result = reason;
        for (RevealedTarget row : rows) {
            result = redact(result, row.accountReference());
            result = redact(result, row.reservationReference());
            result = redact(result, row.ticketReference());
        }
        return result;
    }

    private static String redact(String reason, String reference) {
        return reference == null || reference.isBlank() ? reason
                : reason.replaceAll("(?i)" + java.util.regex.Pattern.quote(reference), "[redacted]");
    }

    private static void validatePage(int page, int pageSize) {
        if (page < 0 || pageSize < 1 || pageSize > PAGE_MAX || (long) page * pageSize > Integer.MAX_VALUE)
            throw badRequest("INVALID_PAGE");
    }

    static byte[] csv(List<RevealedTarget> rows) {
        StringBuilder csv = new StringBuilder("account_id,reservation_reference,ticket_reference,performance_id,purchased_at,reserved_at,seat_zone,seat_row,seat_number,reasons\r\n");
        for (RevealedTarget row : rows) {
            appendCsv(csv, row.accountReference()); csv.append(',');
            appendCsv(csv, row.reservationReference()); csv.append(',');
            appendCsv(csv, row.ticketReference()); csv.append(',');
            appendCsv(csv, row.performanceId().toString()); csv.append(',');
            appendCsv(csv, row.purchasedAt().toString()); csv.append(',');
            appendCsv(csv, row.reservedAt() == null ? null : row.reservedAt().toString()); csv.append(',');
            appendCsv(csv, row.seatZone()); csv.append(',');
            appendCsv(csv, row.seatRow()); csv.append(',');
            appendCsv(csv, row.seatNumber()); csv.append(',');
            appendCsv(csv, String.join(";", row.reasons())); csv.append("\r\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendCsv(StringBuilder output, String value) {
        String safe = value == null ? "" : value;
        int firstVisible = 0;
        while (firstVisible < safe.length() && Character.isWhitespace(safe.charAt(firstVisible))) firstVisible++;
        if (firstVisible < safe.length() && "=+-@".indexOf(safe.charAt(firstVisible)) >= 0) safe = "'" + safe;
        output.append('"').append(safe.replace("\"", "\"\"")).append('"');
    }

    private static String mask(String value) {
        if (value == null || value.isEmpty()) return "****";
        if (value.length() <= 2) return "****";
        return "****" + value.substring(value.length() - 2);
    }

    static Target masked(RevealedTarget decoded) {
        return new Target(decoded.targetId(), mask(decoded.accountReference()), mask(decoded.reservationReference()),
                mask(decoded.ticketReference()), decoded.performanceId(), decoded.purchasedAt(), decoded.ticketStatus(),
                decoded.seatZone(), decoded.seatRow(), decoded.seatNumber(), decoded.reasons(), decoded.reservedAt());
    }

    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found"); }
    private static ResponseStatusException badRequest(String code) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, code); }
    private static ResponseStatusException tooMany() { return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "INVESTIGATION_TARGET_LIMIT_EXCEEDED"); }

    public record Target(String targetId, String accountReference, String reservationReference, String ticketReference,
                         UUID performanceId, LocalDateTime purchasedAt, String ticketStatus, String seatZone,
                         String seatRow, String seatNumber, List<String> reasons, LocalDateTime reservedAt) { }
    public record TargetPage(List<Target> items, int page, int pageSize, int total, boolean hasNext) { }
    public record RevealedTarget(String targetId, String accountReference, String reservationReference, String ticketReference,
                                 UUID performanceId, LocalDateTime purchasedAt, String ticketStatus, String seatZone,
                                 String seatRow, String seatNumber, List<String> reasons, LocalDateTime reservedAt) {
        public RevealedTarget(String targetId, String accountReference, String reservationReference, String ticketReference,
                UUID performanceId, LocalDateTime purchasedAt, String ticketStatus, String seatZone,
                String seatRow, String seatNumber, List<String> reasons) {
            this(targetId, accountReference, reservationReference, ticketReference, performanceId, purchasedAt,
                    ticketStatus, seatZone, seatRow, seatNumber, reasons, null);
        }
        @Override public String toString() { return "RevealedTarget[redacted]"; }
    }
    public record RevealedPage(List<RevealedTarget> items, int page, int pageSize, int total, boolean hasNext) { }
    public record AuditEntry(String actorSubject, String targetType, String resourceId, String action, int targetCount,
                             String reason, java.time.OffsetDateTime accessedAt) { }
    private record StoredTarget(UUID id, UUID importId, UUID tenantId, UUID eventId, UUID performanceId, long rowNumber,
            String ticketHmac, String reservationHmac, String accountHmac, byte[] ciphertext, byte[] nonce, String keyVersion,
            LocalDateTime purchasedAt, String ticketStatus, String seatZone, String seatRow, String seatNumber, LocalDateTime reservedAt,
            List<String> reasons) {
    }
}
