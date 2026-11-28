package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.Exp3ResourceRetentionPolicyDto;
import com.ticket.defender_core.security.AdminRole;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class Exp3ResourceRetentionPolicyService {
    private static final Set<AdminRole> MANAGE_ROLES = Set.of(AdminRole.TENANT_ADMIN);
    private static final String RESOURCE_TYPE = "refund_account_hash";
    private final CandidatePipelineAccess access;
    private final JdbcTemplate jdbc;

    @Transactional
    public Exp3ResourceRetentionPolicyDto.View approve(UUID eventId, Exp3ResourceRetentionPolicyDto.Create input) {
        CandidatePipelineAccess.Scope scope = access.event(eventId, MANAGE_ROLES);
        require(input);
        input = normalized(input);
        jdbc.queryForList("select id from events where id=? and tenant_id=? for update",
                String.class, eventId.toString(), scope.tenantId().toString());
        Timestamp from = timestamp(input.effectiveFrom());
        Timestamp until = input.effectiveUntil() == null ? null : timestamp(input.effectiveUntil());
        Timestamp maximum = Timestamp.valueOf("9999-12-31 23:59:59.999999");
        Integer overlap = jdbc.queryForObject("""
                select count(*) from td_exp3_resource_retention_policies
                where tenant_id=? and event_id=? and supplier_namespace=? and resource_type=?
                  and effective_from < ? and coalesce(effective_until,?) > ?
                """, Integer.class, scope.tenantId().toString(), eventId.toString(), input.supplierNamespace(),
                RESOURCE_TYPE, until == null ? maximum : until, maximum, from);
        if (overlap != null && overlap > 0) throw conflict("EXP3_RETENTION_POLICY_PERIOD_CONFLICT");
        Integer versionExists = jdbc.queryForObject("""
                select count(*) from td_exp3_resource_retention_policies
                where tenant_id=? and event_id=? and supplier_namespace=? and resource_type=? and policy_version=?
                """, Integer.class, scope.tenantId().toString(), eventId.toString(), input.supplierNamespace(),
                RESOURCE_TYPE, input.policyVersion());
        if (versionExists != null && versionExists > 0) throw conflict("EXP3_RETENTION_POLICY_VERSION_REUSE");

        UUID id = UUID.randomUUID();
        OffsetDateTime approvedAt = OffsetDateTime.now(ZoneOffset.UTC);
        try {
            jdbc.update("""
                    insert into td_exp3_resource_retention_policies(
                      id,tenant_id,event_id,supplier_namespace,resource_type,policy_version,retention_days,
                      effective_from,effective_until,policy_source,verification_basis,approved_by_membership_id,approved_at
                    ) values(?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, id.toString(), scope.tenantId().toString(), eventId.toString(), input.supplierNamespace(),
                    RESOURCE_TYPE, input.policyVersion(), input.retentionDays(), from, until,
                    input.policySource().trim(), input.verificationBasis().trim(), scope.membershipId().toString(),
                    timestamp(approvedAt));
        } catch (DataIntegrityViolationException ex) {
            throw conflict("EXP3_RETENTION_POLICY_CONFLICT");
        }
        return view(id, eventId, input, scope.membershipId(), approvedAt);
    }

    @Transactional(readOnly = true)
    public List<Exp3ResourceRetentionPolicyDto.View> list(UUID eventId) {
        CandidatePipelineAccess.Scope scope = access.event(eventId, MANAGE_ROLES);
        return jdbc.query("""
                select id,supplier_namespace,resource_type,policy_version,retention_days,effective_from,effective_until,
                       policy_source,verification_basis,approved_by_membership_id,approved_at
                from td_exp3_resource_retention_policies
                where tenant_id=? and event_id=? order by supplier_namespace,effective_from,policy_version
                """, (rs, row) -> new Exp3ResourceRetentionPolicyDto.View(
                UUID.fromString(rs.getString("id")), eventId, rs.getString("supplier_namespace"),
                rs.getString("resource_type"), rs.getString("policy_version"), rs.getInt("retention_days"),
                offset(rs.getTimestamp("effective_from")), offset(rs.getTimestamp("effective_until")),
                rs.getString("policy_source"), rs.getString("verification_basis"),
                UUID.fromString(rs.getString("approved_by_membership_id")), offset(rs.getTimestamp("approved_at"))),
                scope.tenantId().toString(), eventId.toString());
    }

    private static void require(Exp3ResourceRetentionPolicyDto.Create input) {
        if (input == null || bad(input.supplierNamespace(), 128) || bad(input.policyVersion(), 128)
                || input.retentionDays() == null || input.retentionDays() < 1 || input.effectiveFrom() == null
                || bad(input.policySource(), 512) || bad(input.verificationBasis(), 512)
                || (input.effectiveUntil() != null && !input.effectiveUntil().isAfter(input.effectiveFrom()))) {
            throw new CaseApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        }
    }

    private static Exp3ResourceRetentionPolicyDto.Create normalized(Exp3ResourceRetentionPolicyDto.Create input) {
        return new Exp3ResourceRetentionPolicyDto.Create(input.supplierNamespace().trim(), input.policyVersion().trim(),
                input.retentionDays(), input.effectiveFrom(), input.effectiveUntil(),
                input.policySource().trim(), input.verificationBasis().trim());
    }

    private static boolean bad(String value, int maximum) {
        return value == null || value.isBlank() || value.length() > maximum;
    }

    private static Timestamp timestamp(OffsetDateTime value) {
        return Timestamp.from(value.toInstant());
    }

    private static OffsetDateTime offset(Timestamp value) {
        return value == null ? null : value.toInstant().atOffset(ZoneOffset.UTC);
    }

    private static Exp3ResourceRetentionPolicyDto.View view(UUID id, UUID eventId,
            Exp3ResourceRetentionPolicyDto.Create input, UUID membershipId, OffsetDateTime approvedAt) {
        return new Exp3ResourceRetentionPolicyDto.View(id, eventId, input.supplierNamespace(), RESOURCE_TYPE,
                input.policyVersion(), input.retentionDays(), input.effectiveFrom(), input.effectiveUntil(),
                input.policySource().trim(), input.verificationBasis().trim(), membershipId, approvedAt);
    }

    private static CaseApiException conflict(String code) {
        return new CaseApiException(HttpStatus.CONFLICT, code);
    }
}
