package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.persistence.FanReportRepository;
import com.ticket.defender_core.domain.FanReport;
import com.ticket.defender_core.security.AdminAccessService;
import com.ticket.defender_core.security.AdminActor;
import com.ticket.defender_core.security.AdminResourceScope;
import com.ticket.defender_core.security.AdminRole;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/** 모든 후보 파이프라인 명령의 tenant/event 범위를 저장 자원에서만 결정합니다. */
@Component
@RequiredArgsConstructor
class CandidatePipelineAccess {
    private final AdminAccessService access;
    private final CaseMembershipAccess memberships;
    private final FanReportRepository reports;

    Scope event(UUID eventId, Set<AdminRole> roles) {
        AdminActor actor = access.requireEventAccess(eventId, roles);
        return new Scope(actor.tenantId(), actor.eventId(), memberships.actor(actor).getId(), null);
    }

    Scope report(UUID fanReportId, Set<AdminRole> roles) {
        FanReport report = reports.findById(fanReportId).orElseThrow(CandidatePipelineAccess::notFound);
        AdminActor actor = access.requireResourceAccess(
                new AdminResourceScope(report.getTenantId(), report.getEventId()), null, roles);
        return new Scope(actor.tenantId(), actor.eventId(), memberships.actor(actor).getId(), report);
    }

    record Scope(UUID tenantId, UUID eventId, UUID membershipId, FanReport report) { }

    private static CaseApiException notFound() {
        return new CaseApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }
}
