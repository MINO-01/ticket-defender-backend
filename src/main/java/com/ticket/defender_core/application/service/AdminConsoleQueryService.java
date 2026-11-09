package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.AdminConsoleDto;
import com.ticket.defender_core.adapter.out.persistence.AdminMembershipRepository;
import com.ticket.defender_core.adapter.out.persistence.ApprovalAuditEventRepository;
import com.ticket.defender_core.adapter.out.persistence.ApprovalRequestRepository;
import com.ticket.defender_core.adapter.out.persistence.CaseRepository;
import com.ticket.defender_core.adapter.out.persistence.MembershipEventGrantRepository;
import com.ticket.defender_core.adapter.out.persistence.MembershipRoleRepository;
import com.ticket.defender_core.adapter.out.persistence.Track1PurchaseLimitRunRepository;
import com.ticket.defender_core.domain.AdminMembershipEntity;
import com.ticket.defender_core.domain.ApprovalAuditEvent;
import com.ticket.defender_core.domain.ApprovalRequest;
import com.ticket.defender_core.security.AdminAccessService;
import com.ticket.defender_core.security.AdminActor;
import com.ticket.defender_core.security.AdminRole;
import com.ticket.defender_core.security.TenantSelectionRequiredException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** 시연 관리자 화면의 조회 공백을 채웁니다. 저장된 행만 집계하고 값을 추정하거나 생성하지 않습니다. */
@Service
@RequiredArgsConstructor
public class AdminConsoleQueryService {
    private static final Set<AdminRole> APPROVAL_VIEW = Set.of(AdminRole.TENANT_ADMIN, AdminRole.REVIEWER, AdminRole.APPROVER);
    private static final Set<AdminRole> SUMMARY_VIEW = Set.of(AdminRole.TENANT_ADMIN, AdminRole.ANALYST, AdminRole.REVIEWER, AdminRole.APPROVER);

    private final AdminAccessService access;
    private final AdminMembershipRepository memberships;
    private final MembershipRoleRepository roles;
    private final MembershipEventGrantRepository grants;
    private final ApprovalRequestRepository approvals;
    private final ApprovalAuditEventRepository audits;
    private final CaseRepository cases;
    private final Track1PurchaseLimitRunRepository runs;

    @Transactional(readOnly = true)
    public AdminConsoleDto.Me me() {
        String subject = access.authenticatedSubject();
        List<AdminMembershipEntity> found = memberships.findActiveBySubject(subject, PageRequest.of(0, 2)).stream()
                .filter(item -> subject.equals(item.getSubject())).toList();
        if (found.isEmpty()) throw new CaseApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        if (found.size() > 1) throw new TenantSelectionRequiredException();
        AdminMembershipEntity membership = found.getFirst();
        return new AdminConsoleDto.Me(membership.getTenantId(),
                roles.findAllByMembershipId(membership.getId()).stream().map(role -> role.getRole()).sorted().toList(),
                grants.findAllByMembershipIdAndTenantId(membership.getId(), membership.getTenantId()).stream()
                        .map(grant -> grant.getEventId()).sorted().toList());
    }

    @Transactional(readOnly = true)
    public AdminConsoleDto.Page<AdminConsoleDto.ApprovalRequestItem> approvalRequests(UUID eventId, String status, int page, int pageSize) {
        Track1QuantityLedgerService.validatePage(page, pageSize);
        ApprovalRequest.Status parsed = status == null ? null : parseStatus(status);
        AdminActor actor = access.requireEventAccess(eventId, APPROVAL_VIEW);
        PageRequest request = PageRequest.of(page, pageSize, Sort.by("requestedAt").descending().and(Sort.by("id").descending()));
        var found = parsed == null ? approvals.findByTenantIdAndEventId(actor.tenantId(), actor.eventId(), request)
                : approvals.findByTenantIdAndEventIdAndStatus(actor.tenantId(), actor.eventId(), parsed, request);
        return new AdminConsoleDto.Page<>(found.stream().map(item -> new AdminConsoleDto.ApprovalRequestItem(item.getId(), item.getCaseId(), item.getReportVersionId(),
                item.getReportRevision(), item.getEvidenceRevision(), item.getStatus().name(), item.getRequestedAt().atOffset(ZoneOffset.UTC))).toList(),
                page, pageSize, found.hasNext());
    }

    @Transactional(readOnly = true)
    public AdminConsoleDto.Page<AdminConsoleDto.AuditEventItem> auditEvents(UUID eventId, int page, int pageSize) {
        Track1QuantityLedgerService.validatePage(page, pageSize);
        AdminActor actor = access.requireEventAccess(eventId, APPROVAL_VIEW);
        var found = audits.findByTenantIdAndEventId(actor.tenantId(), actor.eventId(),
                PageRequest.of(page, pageSize, Sort.by("occurredAt").descending().and(Sort.by("id").descending())));
        return new AdminConsoleDto.Page<>(found.stream().map(AdminConsoleQueryService::audit).toList(), page, pageSize, found.hasNext());
    }

    @Transactional(readOnly = true)
    public AdminConsoleDto.Summary summary(UUID eventId) {
        AdminActor actor = access.requireEventAccess(eventId, SUMMARY_VIEW);
        return new AdminConsoleDto.Summary(eventId, counts(cases.countByStatus(actor.tenantId(), actor.eventId())),
                counts(approvals.countByStatus(actor.tenantId(), actor.eventId())),
                counts(runs.countByExecutionStatus(actor.tenantId(), actor.eventId())), "PERSISTED_ROWS_ONLY");
    }

    private static AdminConsoleDto.AuditEventItem audit(ApprovalAuditEvent item) {
        return new AdminConsoleDto.AuditEventItem(item.getId(), item.getCaseId(), item.getApprovalRequestId(), item.getActorMembershipId(),
                item.getEventType(), item.getFromStatus(), item.getToStatus(), item.getOccurredAt().atOffset(ZoneOffset.UTC));
    }

    private static Map<String, Long> counts(List<Object[]> rows) {
        Map<String, Long> result = new TreeMap<>();
        rows.forEach(row -> result.put(row[0].toString(), ((Number) row[1]).longValue()));
        return result;
    }

    private static ApprovalRequest.Status parseStatus(String status) {
        try {
            return ApprovalRequest.Status.valueOf(status.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw CandidatePipelineSupport.invalid();
        }
    }
}
