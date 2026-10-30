package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.persistence.AdminMembershipRepository;
import com.ticket.defender_core.adapter.out.persistence.CaseActivityRepository;
import com.ticket.defender_core.adapter.out.persistence.CaseRepository;
import com.ticket.defender_core.adapter.out.persistence.IssuanceHoldRepository;
import com.ticket.defender_core.adapter.out.persistence.MembershipEventGrantRepository;
import com.ticket.defender_core.domain.AccessRecordStatus;
import com.ticket.defender_core.domain.AdminMembershipEntity;
import com.ticket.defender_core.domain.Case;
import com.ticket.defender_core.domain.CaseActivity;
import com.ticket.defender_core.domain.IssuanceHold;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Non-web internal boundary for Hold writers. It shares Case-first locking with official PDF
 * finalization; direct repository writes are not an approved replacement for this boundary.
 * It has no web controller and is not a public Hold-release API.
 */
@Service
@RequiredArgsConstructor
public class InternalIssuanceHoldWriteService {
    private final CaseRepository cases;
    private final IssuanceHoldRepository holds;
    private final AdminMembershipRepository memberships;
    private final MembershipEventGrantRepository grants;
    private final CaseActivityRepository activities;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public IssuanceHold place(UUID caseId, UUID actorMembershipId, String reasonCode) {
        Case target = cases.findByIdForUpdate(caseId).orElseThrow(InternalIssuanceHoldWriteService::notFound);
        requireTrustedScope(target, actorMembershipId);
        if (reasonCode == null || reasonCode.isBlank() || reasonCode.length() > 64)
            throw new CaseApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        IssuanceHold hold = holds.saveAndFlush(new IssuanceHold(target, reasonCode.trim(), actorMembershipId, now));
        audit(target, actorMembershipId, "ISSUANCE_HOLD_PLACED", hold, now);
        return hold;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public IssuanceHold release(UUID holdId, UUID actorMembershipId) {
        UUID caseId = holds.findCaseIdById(holdId).orElseThrow(InternalIssuanceHoldWriteService::notFound);
        Case target = cases.findByIdForUpdate(caseId).orElseThrow(InternalIssuanceHoldWriteService::notFound);
        IssuanceHold hold = holds.findByIdForUpdate(holdId).orElseThrow(InternalIssuanceHoldWriteService::notFound);
        if (!target.getId().equals(hold.getCaseId()) || !target.getTenantId().equals(hold.getTenantId())
                || !target.getEventId().equals(hold.getEventId())) throw notFound();
        requireTrustedScope(target, actorMembershipId);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        hold.release(now);
        IssuanceHold released = holds.saveAndFlush(hold);
        audit(target, actorMembershipId, "ISSUANCE_HOLD_RELEASED", released, now);
        return released;
    }

    /** Same transaction as the Hold write: an audit failure rolls the Hold change back. */
    private void audit(Case target, UUID actorMembershipId, String eventType, IssuanceHold hold, LocalDateTime now) {
        activities.saveAndFlush(new CaseActivity(target, actorMembershipId, "SYSTEM", eventType,
                "issuanceHold=" + hold.getId() + "; reasonCode=" + hold.getReasonCode(), null, null,
                target.getCaseVersion(), target.getCaseVersion(), null, now));
    }

    private void requireTrustedScope(Case target, UUID memberId) {
        AdminMembershipEntity member = memberships.findById(memberId)
                .filter(found -> target.getTenantId().equals(found.getTenantId())
                        && AccessRecordStatus.ACTIVE.name().equals(found.getStatus()))
                .orElseThrow(InternalIssuanceHoldWriteService::notFound);
        if (grants.findAllByMembershipIdAndTenantId(member.getId(), target.getTenantId()).stream()
                .noneMatch(grant -> target.getEventId().equals(grant.getEventId()))) throw notFound();
    }

    private static CaseApiException notFound() {
        return new CaseApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }
}
