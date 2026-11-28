package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.persistence.BulkLedgerImportScopeRepository;
import com.ticket.defender_core.adapter.out.persistence.BulkLedgerImportSupplierScopeRepository;
import com.ticket.defender_core.domain.BulkLedgerImportScope;
import com.ticket.defender_core.domain.BulkLedgerImportSupplierScope;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** Resolves supplier codes only after the complete CSV has passed preflight validation. */
@Service
public class BulkLedgerSupplierScopeProvisioner {
    private final JdbcTemplate jdbc;
    private final BulkLedgerImportSupplierScopeRepository supplierScopes;
    private final BulkLedgerImportScopeRepository scopes;

    public BulkLedgerSupplierScopeProvisioner(JdbcTemplate jdbc,
            BulkLedgerImportSupplierScopeRepository supplierScopes, BulkLedgerImportScopeRepository scopes) {
        this.jdbc = jdbc;
        this.supplierScopes = supplierScopes;
        this.scopes = scopes;
    }

    @Transactional
    public void resolve(UUID jobId, UUID tenantId, UUID membershipId) {
        List<BulkLedgerImportSupplierScope> requested = supplierScopes
                .findByJobIdOrderBySourceEventCodeAscSourcePerformanceCodeAsc(jobId);
        if (requested.isEmpty()) throw rejected("BULK_CSV_SUPPLIER_SCOPE_REQUIRED");
        if (requested.stream().anyMatch(scope -> !tenantId.equals(scope.getTenantId())))
            throw rejected("BULK_CSV_SCOPE_TENANT_MISMATCH");

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        for (BulkLedgerImportSupplierScope scope : requested) {
            ResolvedEvent event = resolveEvent(tenantId, membershipId, scope);
            ResolvedPerformance performance = resolvePerformance(tenantId, membershipId, scope, event.id(), now);
            if (scope.getEventId() != null && (!scope.getEventId().equals(event.id())
                    || !scope.getPerformanceId().equals(performance.id())))
                throw rejected("BULK_SUPPLIER_SCOPE_RESOLUTION_CONFLICT");
            scope.resolve(event.id(), performance.id(), event.created(), performance.created());
            if (scopes.findByJobIdOrderByEventIdAscPerformanceIdAsc(jobId).stream()
                    .noneMatch(existing -> existing.getEventId().equals(event.id()) && existing.getPerformanceId().equals(performance.id()))) {
                scopes.save(new BulkLedgerImportScope(jobId, tenantId, event.id(), performance.id(),
                        scope.getScopeAsOf(), scope.getDeclaredRowCount(), scope.getCursorStart(), scope.getCursorEnd(),
                        scope.getSourceSequenceStart(), scope.getSourceSequenceEnd()));
            }
        }
        supplierScopes.saveAllAndFlush(requested);
        scopes.flush();
    }

    private ResolvedEvent resolveEvent(UUID tenantId, UUID membershipId, BulkLedgerImportSupplierScope scope) {
        List<ResolvedEvent> found = jdbc.query("select id, external_event_ref, name, status from events where tenant_id=? and external_event_ref=? for update",
                (rs, row) -> new ResolvedEvent(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3), rs.getString(4), false),
                tenantId.toString(), scope.getSourceEventCode());
        if (!found.isEmpty()) {
            ResolvedEvent event = found.getFirst();
            if (!scope.getSourceEventCode().equals(event.code())) throw rejected("BULK_SUPPLIER_EVENT_CODE_COLLISION");
            if (!"ACTIVE".equals(event.status())) throw rejected("BULK_SUPPLIER_EVENT_INACTIVE");
            if (!scope.getEventTitle().equals(event.name())) throw rejected("BULK_CSV_EVENT_METADATA_MISMATCH");
            if (!hasGrant(tenantId, membershipId, event.id())) throw rejected("BULK_CSV_SCOPE_FORBIDDEN");
            return event;
        }

        UUID id = UUID.randomUUID();
        try {
            jdbc.update("insert into events(id, external_event_ref, name, public_intake_enabled, status, tenant_id) values(?,?,?,?,?,?)",
                    id.toString(), scope.getSourceEventCode(), scope.getEventTitle(), false, "ACTIVE", tenantId.toString());
        } catch (DuplicateKeyException collision) {
            List<ResolvedEvent> raced = jdbc.query("select id, external_event_ref, name, status from events where tenant_id=? and external_event_ref=? for update",
                    (rs, row) -> new ResolvedEvent(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3), rs.getString(4), false),
                    tenantId.toString(), scope.getSourceEventCode());
            if (raced.isEmpty()) throw rejected("BULK_SUPPLIER_EVENT_CODE_COLLISION");
            ResolvedEvent event = raced.getFirst();
            if (!scope.getSourceEventCode().equals(event.code())) throw rejected("BULK_SUPPLIER_EVENT_CODE_COLLISION");
            if (!"ACTIVE".equals(event.status())) throw rejected("BULK_SUPPLIER_EVENT_INACTIVE");
            if (!scope.getEventTitle().equals(event.name())) throw rejected("BULK_CSV_EVENT_METADATA_MISMATCH");
            if (!hasGrant(tenantId, membershipId, event.id())) throw rejected("BULK_CSV_SCOPE_FORBIDDEN");
            return event;
        }
        grant(tenantId, membershipId, id);
        return new ResolvedEvent(id, scope.getSourceEventCode(), scope.getEventTitle(), "ACTIVE", true);
    }

    private ResolvedPerformance resolvePerformance(UUID tenantId, UUID membershipId,
            BulkLedgerImportSupplierScope scope, UUID eventId, LocalDateTime now) {
        List<ResolvedPerformance> found = findPerformance(tenantId, eventId, scope.getSourcePerformanceCode());
        if (!found.isEmpty()) {
            if (!scope.getSourcePerformanceCode().equals(found.getFirst().code())) throw rejected("BULK_SUPPLIER_PERFORMANCE_CODE_COLLISION");
            return validateOrEnrich(found.getFirst(), scope, eventId);
        }

        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    insert into verified_performances(id, event_id, performance_ref, revision, row_version,
                        source_version, status, tenant_id, verification_basis, verified_at,
                        verified_by_membership_id, performance_starts_at, supplier_per_account_limit)
                    values(?,?,?,1,0,'SUPPLIER_UPLOAD','ACTIVE',?,'SUPPLIER_UPLOAD',?,?,?,?)
                    """, id.toString(), eventId.toString(), scope.getSourcePerformanceCode(), tenantId.toString(),
                    now, membershipId.toString(), scope.getPerformanceStartsAt(), scope.getPerAccountLimit());
        } catch (DuplicateKeyException collision) {
            List<ResolvedPerformance> raced = findPerformance(tenantId, eventId, scope.getSourcePerformanceCode());
            if (raced.isEmpty()) throw rejected("BULK_SUPPLIER_PERFORMANCE_CODE_COLLISION");
            if (!scope.getSourcePerformanceCode().equals(raced.getFirst().code())) throw rejected("BULK_SUPPLIER_PERFORMANCE_CODE_COLLISION");
            return validateOrEnrich(raced.getFirst(), scope, eventId);
        }
        return new ResolvedPerformance(id, scope.getSourcePerformanceCode(), scope.getPerformanceStartsAt(), scope.getPerAccountLimit(), "ACTIVE", true);
    }

    private ResolvedPerformance validateOrEnrich(ResolvedPerformance current, BulkLedgerImportSupplierScope scope,
            UUID eventId) {
        if (!"ACTIVE".equals(current.status())) throw rejected("BULK_SUPPLIER_PERFORMANCE_INACTIVE");
        if (current.startsAt() != null && !current.startsAt().equals(scope.getPerformanceStartsAt()))
            throw rejected("BULK_CSV_PERFORMANCE_METADATA_MISMATCH");
        if (scope.getPerAccountLimit() != null && current.accountLimit() != null
                && !current.accountLimit().equals(scope.getPerAccountLimit()))
            throw rejected("BULK_CSV_PERFORMANCE_METADATA_MISMATCH");
        if (current.startsAt() == null || current.accountLimit() == null && scope.getPerAccountLimit() != null) {
            jdbc.update("update verified_performances set performance_starts_at=coalesce(performance_starts_at,?), "
                            + "supplier_per_account_limit=coalesce(supplier_per_account_limit,?) where id=? and tenant_id=? and event_id=?",
                    scope.getPerformanceStartsAt(), scope.getPerAccountLimit(), current.id().toString(),
                    scope.getTenantId().toString(), eventId.toString());
        }
        return new ResolvedPerformance(current.id(), current.code(), scope.getPerformanceStartsAt(),
                scope.getPerAccountLimit() == null ? current.accountLimit() : scope.getPerAccountLimit(), current.status(), false);
    }

    private List<ResolvedPerformance> findPerformance(UUID tenantId, UUID eventId, String code) {
        return jdbc.query("""
                select id, performance_ref, performance_starts_at, supplier_per_account_limit, status from verified_performances
                where tenant_id=? and event_id=? and performance_ref=? for update
                """, (rs, row) -> new ResolvedPerformance(UUID.fromString(rs.getString(1)), rs.getString(2),
                rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toLocalDateTime(),
                (Integer) rs.getObject(4), rs.getString(5), false), tenantId.toString(), eventId.toString(), code);
    }

    private boolean hasGrant(UUID tenantId, UUID membershipId, UUID eventId) {
        Integer count = jdbc.queryForObject("select count(*) from membership_events where tenant_id=? and membership_id=? and event_id=?",
                Integer.class, tenantId.toString(), membershipId.toString(), eventId.toString());
        return count != null && count > 0;
    }

    private void grant(UUID tenantId, UUID membershipId, UUID eventId) {
        try {
            jdbc.update("insert into membership_events(id,event_id,membership_id,tenant_id) values(?,?,?,?)",
                    UUID.randomUUID().toString(), eventId.toString(), membershipId.toString(), tenantId.toString());
        } catch (DuplicateKeyException alreadyGranted) {
            if (!hasGrant(tenantId, membershipId, eventId)) throw alreadyGranted;
        }
    }

    private static BulkLedgerCsvValidationException rejected(String code) {
        return new BulkLedgerCsvValidationException(0, code);
    }

    private record ResolvedEvent(UUID id, String code, String name, String status, boolean created) { }
    private record ResolvedPerformance(UUID id, String code, LocalDateTime startsAt, Integer accountLimit, String status, boolean created) { }
}
