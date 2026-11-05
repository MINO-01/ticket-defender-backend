package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.CandidatePipelineDto;
import com.ticket.defender_core.adapter.out.persistence.PerformanceVerificationAuditRepository;
import com.ticket.defender_core.adapter.out.persistence.VerifiedPerformanceRepository;
import com.ticket.defender_core.domain.PerformanceVerificationAudit;
import com.ticket.defender_core.domain.VerifiedPerformance;
import com.ticket.defender_core.security.AdminRole;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class VerifiedPerformanceService {
    private static final Set<AdminRole> MANAGE_ROLES = Set.of(AdminRole.REVIEWER, AdminRole.TENANT_ADMIN);

    private final CandidatePipelineAccess access;
    private final VerifiedPerformanceRepository performances;
    private final PerformanceVerificationAuditRepository audits;

    @Transactional
    public CandidatePipelineDto.Performance create(UUID eventId, CandidatePipelineDto.CreatePerformance input) {
        CandidatePipelineAccess.Scope scope = access.event(eventId, MANAGE_ROLES);
        String ref = CandidatePipelineSupport.bounded(input == null ? null : input.performanceRef(), 128);
        String basis = CandidatePipelineSupport.bounded(input == null ? null : input.verificationBasis(), 512);
        String sourceVersion = CandidatePipelineSupport.bounded(input == null ? null : input.sourceVersion(), 128);
        if (performances.findByTenantIdAndEventIdAndPerformanceRef(scope.tenantId(), eventId, ref).isPresent())
            throw conflict("PERFORMANCE_REF_CONFLICT");
        LocalDateTime now = now();
        VerifiedPerformance performance = new VerifiedPerformance(UUID.randomUUID(), scope.tenantId(), eventId, ref,
                basis, sourceVersion, scope.membershipId(), now);
        try {
            performances.saveAndFlush(performance);
        } catch (DataIntegrityViolationException collision) {
            // The unique scope/ref key is the final arbiter when two reviewers verify the same ref concurrently.
            throw conflict("PERFORMANCE_REF_CONFLICT");
        }
        audits.save(new PerformanceVerificationAudit(performance, PerformanceVerificationAudit.Action.VERIFIED,
                scope.membershipId(), UUID.randomUUID(), now));
        return view(performance);
    }

    @Transactional
    public CandidatePipelineDto.Performance revise(UUID eventId, UUID performanceId,
            CandidatePipelineDto.RevisePerformance input) {
        CandidatePipelineAccess.Scope scope = access.event(eventId, MANAGE_ROLES);
        if (input == null || input.expectedRevision() < 1) throw CandidatePipelineSupport.invalid();
        VerifiedPerformance performance = performances.lockByIdAndScope(performanceId, scope.tenantId(), eventId)
                .orElseThrow(VerifiedPerformanceService::notFound);
        if (performance.getRevision() != input.expectedRevision()) throw conflict("PERFORMANCE_REVISION_CONFLICT");
        LocalDateTime now = now();
        try {
            performance.revise(CandidatePipelineSupport.bounded(input.verificationBasis(), 512),
                    CandidatePipelineSupport.bounded(input.sourceVersion(), 128), scope.membershipId(), now);
        } catch (IllegalStateException invalid) {
            throw conflict("PERFORMANCE_WITHDRAWN");
        }
        performances.saveAndFlush(performance);
        audits.save(new PerformanceVerificationAudit(performance, PerformanceVerificationAudit.Action.REVISED,
                scope.membershipId(), UUID.randomUUID(), now));
        return view(performance);
    }

    @Transactional
    public CandidatePipelineDto.Performance withdraw(UUID eventId, UUID performanceId,
            CandidatePipelineDto.WithdrawPerformance input) {
        CandidatePipelineAccess.Scope scope = access.event(eventId, MANAGE_ROLES);
        if (input == null || input.expectedRevision() < 1) throw CandidatePipelineSupport.invalid();
        VerifiedPerformance performance = performances.lockByIdAndScope(performanceId, scope.tenantId(), eventId)
                .orElseThrow(VerifiedPerformanceService::notFound);
        if (performance.getRevision() != input.expectedRevision()) throw conflict("PERFORMANCE_REVISION_CONFLICT");
        LocalDateTime now = now();
        try {
            performance.withdraw(CandidatePipelineSupport.bounded(input.reason(), 512), scope.membershipId(), now);
        } catch (IllegalStateException invalid) {
            throw conflict("PERFORMANCE_WITHDRAWN");
        }
        performances.saveAndFlush(performance);
        audits.save(new PerformanceVerificationAudit(performance, PerformanceVerificationAudit.Action.WITHDRAWN,
                scope.membershipId(), UUID.randomUUID(), now));
        return view(performance);
    }

    @Transactional(readOnly = true)
    public List<CandidatePipelineDto.Performance> list(UUID eventId) {
        CandidatePipelineAccess.Scope scope = access.event(eventId, MANAGE_ROLES);
        return performances.findByTenantIdAndEventIdOrderByPerformanceRefAsc(scope.tenantId(), eventId).stream()
                .map(VerifiedPerformanceService::view).toList();
    }

    static CandidatePipelineDto.Performance view(VerifiedPerformance item) {
        return new CandidatePipelineDto.Performance(item.getId(), item.getEventId(), item.getPerformanceRef(),
                item.getStatus(), item.getRevision(), item.getSourceVersion(), item.getVerifiedAt(), item.getWithdrawnAt());
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static CaseApiException conflict(String code) {
        return new CaseApiException(HttpStatus.CONFLICT, code);
    }

    private static CaseApiException notFound() {
        return new CaseApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }
}
