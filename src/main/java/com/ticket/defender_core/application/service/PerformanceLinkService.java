package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.CandidatePipelineDto;
import com.ticket.defender_core.adapter.out.persistence.FanReportPerformanceLinkRepository;
import com.ticket.defender_core.adapter.out.persistence.PerformanceLinkAuditRepository;
import com.ticket.defender_core.adapter.out.persistence.VerifiedPerformanceRepository;
import com.ticket.defender_core.domain.FanReportPerformanceLink;
import com.ticket.defender_core.domain.PerformanceLinkAudit;
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

/** 회차 연결을 명시적·멱등적으로 관리하고 이전 연결을 덮어쓰지 않습니다. */
@Service
@RequiredArgsConstructor
public class PerformanceLinkService {
    private static final Set<AdminRole> MANAGE_ROLES = Set.of(AdminRole.REVIEWER, AdminRole.TENANT_ADMIN);

    private final CandidatePipelineAccess access;
    private final FanReportPerformanceLinkRepository links;
    private final PerformanceLinkAuditRepository audits;
    private final VerifiedPerformanceRepository performances;

    @Transactional
    public CandidatePipelineDto.PerformanceLink link(UUID fanReportId, String idempotencyKey,
            CandidatePipelineDto.LinkPerformance input) {
        CandidatePipelineAccess.Scope scope = access.report(fanReportId, MANAGE_ROLES);
        if (input == null || input.performanceId() == null || input.expectedLinkRevision() < 0)
            throw CandidatePipelineSupport.invalid();
        String keyDigest = CandidatePipelineSupport.idempotencyDigest(idempotencyKey);
        String reason = CandidatePipelineSupport.bounded(input.reason(), 512);
        String requestDigest = CandidatePipelineSupport.sha256("LINK", input.performanceId().toString(),
                Integer.toString(input.expectedLinkRevision()), reason);
        var replay = audits.findByActorMembershipIdAndFanReportIdAndIdempotencyKeyDigest(
                scope.membershipId(), fanReportId, keyDigest);
        if (replay.isPresent()) return replay(replay.get(), requestDigest);

        FanReportPerformanceLink active = links.lockActiveByFanReportId(fanReportId).orElse(null);
        int actualRevision = active == null ? 0 : active.getLinkRevision();
        if (actualRevision != input.expectedLinkRevision()) throw conflict("PERFORMANCE_LINK_REVISION_CONFLICT");
        VerifiedPerformance performance = performances.lockByIdAndScope(input.performanceId(), scope.tenantId(),
                scope.eventId()).orElseThrow(PerformanceLinkService::notFound);
        if (performance.getStatus() != VerifiedPerformance.Status.ACTIVE) throw conflict("PERFORMANCE_WITHDRAWN");
        if (active != null && active.getPerformanceId().equals(performance.getId())
                && active.getPerformanceRevision() == performance.getRevision())
            throw conflict("PERFORMANCE_LINK_ALREADY_CURRENT");
        LocalDateTime now = now();
        if (active != null) {
            active.replace(scope.membershipId(), now);
            // Clear the nullable unique active slot before inserting its successor.  This matters on MySQL,
            // where an INSERT is not guaranteed to be ordered after Hibernate's dirty UPDATE otherwise.
            links.saveAndFlush(active);
        }
        FanReportPerformanceLink next = new FanReportPerformanceLink(scope.tenantId(), scope.eventId(), fanReportId,
                performance, actualRevision + 1, reason, scope.membershipId(), now);
        try {
            links.saveAndFlush(next);
            audits.saveAndFlush(new PerformanceLinkAudit(next,
                    active == null ? PerformanceLinkAudit.Action.LINKED : PerformanceLinkAudit.Action.REPLACED,
                    reason, scope.membershipId(), keyDigest, requestDigest, UUID.randomUUID(), now));
        } catch (DataIntegrityViolationException collision) {
            // A concurrent active-link insert or idempotency audit wins; the caller can retry the same key.
            throw conflict("PERFORMANCE_LINK_CONCURRENT_UPDATE");
        }
        return view(next);
    }

    @Transactional
    public CandidatePipelineDto.PerformanceLink withdraw(UUID fanReportId, String idempotencyKey,
            CandidatePipelineDto.WithdrawLink input) {
        CandidatePipelineAccess.Scope scope = access.report(fanReportId, MANAGE_ROLES);
        if (input == null || input.expectedLinkRevision() < 1) throw CandidatePipelineSupport.invalid();
        String keyDigest = CandidatePipelineSupport.idempotencyDigest(idempotencyKey);
        String reason = CandidatePipelineSupport.bounded(input.reason(), 512);
        String requestDigest = CandidatePipelineSupport.sha256("WITHDRAW", Integer.toString(input.expectedLinkRevision()),
                reason);
        var replay = audits.findByActorMembershipIdAndFanReportIdAndIdempotencyKeyDigest(
                scope.membershipId(), fanReportId, keyDigest);
        if (replay.isPresent()) return replay(replay.get(), requestDigest);

        FanReportPerformanceLink active = links.lockActiveByFanReportId(fanReportId)
                .orElseThrow(PerformanceLinkService::notFound);
        if (active.getLinkRevision() != input.expectedLinkRevision())
            throw conflict("PERFORMANCE_LINK_REVISION_CONFLICT");
        LocalDateTime now = now();
        active.withdraw(scope.membershipId(), now);
        links.saveAndFlush(active);
        audits.saveAndFlush(new PerformanceLinkAudit(active, PerformanceLinkAudit.Action.WITHDRAWN, reason,
                scope.membershipId(), keyDigest, requestDigest, UUID.randomUUID(), now));
        return view(active);
    }

    @Transactional(readOnly = true)
    public CandidatePipelineDto.PerformanceLinkHistory history(UUID fanReportId) {
        access.report(fanReportId, MANAGE_ROLES);
        List<CandidatePipelineDto.PerformanceLink> items = links.findByFanReportIdOrderByCreatedAtDesc(fanReportId)
                .stream().map(PerformanceLinkService::view).toList();
        return new CandidatePipelineDto.PerformanceLinkHistory(items);
    }

    private CandidatePipelineDto.PerformanceLink replay(PerformanceLinkAudit audit, String requestDigest) {
        if (!audit.getRequestDigest().equals(requestDigest)) throw conflict("IDEMPOTENCY_KEY_REUSED");
        return links.findById(audit.getLinkId()).map(PerformanceLinkService::view)
                .orElseThrow(PerformanceLinkService::notFound);
    }

    static CandidatePipelineDto.PerformanceLink view(FanReportPerformanceLink link) {
        return new CandidatePipelineDto.PerformanceLink(link.getId(), link.getFanReportId(), link.getPerformanceId(),
                link.getPerformanceRef(), link.getPerformanceRevision(), link.getLinkRevision(),
                link.getStatus().name(), link.getCreatedAt(), link.getEndedAt());
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
