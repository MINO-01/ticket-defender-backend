package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.CaseDto;
import com.ticket.defender_core.adapter.out.persistence.CaseActivityRepository;
import com.ticket.defender_core.adapter.out.persistence.CaseIdempotencyRepository;
import com.ticket.defender_core.adapter.out.persistence.CaseRepository;
import com.ticket.defender_core.adapter.out.persistence.CaseSourceReferenceRepository;
import com.ticket.defender_core.domain.Case;
import com.ticket.defender_core.domain.CaseActivity;
import com.ticket.defender_core.domain.CaseIdempotencyRecord;
import com.ticket.defender_core.domain.CaseSourceReference;
import com.ticket.defender_core.security.AdminAccessService;
import com.ticket.defender_core.security.AdminActor;
import com.ticket.defender_core.security.AdminResourceScope;
import com.ticket.defender_core.security.AdminRole;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CaseWriteService {
    private static final Set<AdminRole> CREATE_ROLES = Set.of(AdminRole.ANALYST, AdminRole.REVIEWER);
    private static final Set<AdminRole> REVIEWER = Set.of(AdminRole.REVIEWER);

    private final AdminAccessService access;
    private final CaseMembershipAccess membershipAccess;
    private final CaseSourceVerifier sourceVerifier;
    private final CaseRepository cases;
    private final CaseSourceReferenceRepository sources;
    private final CaseActivityRepository activities;
    private final CaseIdempotencyRepository idempotency;
    private final ObjectMapper json;

    @Transactional
    public CaseDto.View create(CaseDto.Create input, UUID actorId,
            String keyDigest, String requestDigest) {
        AdminActor actor = access.requireEventAccess(input.eventId(), CREATE_ROLES);
        requireSameActor(actor, actorId);
        String actorRole = membershipAccess.roles(actorId).contains(AdminRole.REVIEWER)
                ? AdminRole.REVIEWER.name() : AdminRole.ANALYST.name();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        validateText(input.reason(), 3, 2000);
        boolean related = input.relatedCaseId() != null;
        if (related != (input.relationReason() != null)) throw invalid();
        if (related) {
            validateText(input.relationReason(), 3, 2000);
            Case previous = cases.findById(input.relatedCaseId()).orElseThrow(CaseWriteService::notFound);
            if (!actor.tenantId().equals(previous.getTenantId())
                    || !actor.eventId().equals(previous.getEventId())) throw notFound();
            if (previous.getStatus() != Case.Status.DISMISSED && previous.getStatus() != Case.Status.CLOSED)
                throw conflict("CASE_STATE_CONFLICT");
        }
        Case target = new Case(UUID.randomUUID(), actor.tenantId(), actor.eventId(), actorId,
                input.type(), input.relatedCaseId(), input.relationReason(), now);
        List<CaseSourceReference> verified = sourceVerifier.verify(target, input, now);
        for (CaseSourceReference reference : verified) {
            List<CaseSourceReference> existingReferences = sources.findByTenantIdAndEventIdAndSourceKeyDigest(
                    actor.tenantId(), actor.eventId(), reference.getSourceKeyDigest());
            boolean relatedCaseReferencesSource = false;
            for (CaseSourceReference existing : existingReferences) {
                Case other = cases.findById(existing.getCaseId()).orElseThrow(CaseWriteService::notFound);
                if (other.getStatus() != Case.Status.DISMISSED && other.getStatus() != Case.Status.CLOSED)
                    throw conflict("SOURCE_ALREADY_LINKED");
                if (Objects.equals(input.relatedCaseId(), other.getId())) relatedCaseReferencesSource = true;
            }
            if (!existingReferences.isEmpty() && !relatedCaseReferencesSource)
                throw conflict("SOURCE_ALREADY_LINKED");
        }
        cases.saveAndFlush(target);
        sources.saveAllAndFlush(verified);
        activities.save(new CaseActivity(target, actorId, actorRole,
                related ? "FOLLOW_UP_CASE_OPENED" : "CASE_OPENED", input.reason(),
                null, Case.Status.OPEN, null, target.getCaseVersion(), null, now));
        for (CaseSourceReference ref : verified)
            activities.save(new CaseActivity(target, actorId, actorRole, "CASE_SOURCE_LINKED", input.reason(),
                    null, Case.Status.OPEN, null, target.getCaseVersion(), ref.getId(), now));
        activities.flush();
        CaseDto.View result = CaseDto.View.from(target);
        idempotency.saveAndFlush(new CaseIdempotencyRecord(actor.tenantId(), actorId, "CREATE",
                CaseService.CREATE_TARGET, keyDigest, requestDigest, target.getId(),
                json.writeValueAsString(result), now));
        return result;
    }

    @Transactional
    public CaseDto.View transition(UUID caseId, CaseDto.Transition input, UUID actorId,
            String keyDigest, String requestDigest) {
        Case target = reviewerCase(caseId, actorId);
        checkVersion(target, input.expectedCaseVersion());
        validateText(input.reason(), 3, 2000);
        Case.Status from = target.getStatus();
        Case.Status to = input.toStatus();
        if (to == null) throw invalid();
        String eventType;
        String auditReason = input.reason().trim();
        String classification = null;
        if (from == Case.Status.OPEN && to == Case.Status.TRIAGED) {
            validateText(input.classification(), 3, 160);
            if (target.getAssigneeMembershipId() == null)
                validateText(input.unassignedReason(), 3, 500);
            else requireCurrentAssignee(target);
            classification = input.classification().trim();
            if (target.getAssigneeMembershipId() == null)
                auditReason = combine(auditReason, input.unassignedReason());
            eventType = "CASE_TRIAGED";
        } else if (from == Case.Status.TRIAGED && to == Case.Status.IN_REVIEW) {
            requireCurrentAssignee(target);
            eventType = "CASE_REVIEW_STARTED";
        } else if (from == Case.Status.IN_REVIEW && to == Case.Status.NEEDS_EVIDENCE) {
            requireCurrentAssignee(target);
            validateText(input.missingEvidence(), 3, 500);
            validateText(input.requestedAction(), 3, 500);
            auditReason = combine(auditReason, input.missingEvidence(), input.requestedAction());
            eventType = "CASE_EVIDENCE_REQUESTED";
        } else if (from == Case.Status.NEEDS_EVIDENCE && to == Case.Status.IN_REVIEW) {
            requireCurrentAssignee(target);
            // W04 has no source-addition command. Do not claim new evidence was attached.
            validateText(input.unavailableReason(), 3, 500);
            auditReason = combine(auditReason, input.unavailableReason());
            eventType = "CASE_REVIEW_RESUMED";
        } else if (from == Case.Status.IN_REVIEW && to == Case.Status.DISMISSED) {
            requireCurrentAssignee(target);
            if (input.dismissalBasis() == null || input.reason().trim().length() < 10) throw invalid();
            auditReason = combine(input.dismissalBasis().name(), auditReason);
            eventType = "CASE_DISMISSED";
        } else {
            throw conflict("CASE_STATE_CONFLICT");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        long before = target.getCaseVersion();
        target.transition(to, classification, now);
        cases.saveAndFlush(target);
        activities.saveAndFlush(new CaseActivity(target, actorId, AdminRole.REVIEWER.name(), eventType, auditReason,
                from, to, before, target.getCaseVersion(), null, now));
        CaseDto.View result = CaseDto.View.from(target);
        idempotency.saveAndFlush(new CaseIdempotencyRecord(target.getTenantId(), actorId, "TRANSITION",
                target.getId(), keyDigest, requestDigest, target.getId(),
                json.writeValueAsString(result), now));
        return result;
    }

    @Transactional
    public CaseDto.View assign(UUID caseId, CaseDto.Assign input, UUID actorId,
            String keyDigest, String requestDigest) {
        Case target = reviewerCase(caseId, actorId);
        checkVersion(target, input.expectedCaseVersion());
        validateText(input.reason(), 3, 2000);
        if (input.assigneeMembershipId() == null) throw invalid();
        if (Objects.equals(input.assigneeMembershipId(), target.getAssigneeMembershipId()))
            throw conflict("CASE_STATE_CONFLICT");
        membershipAccess.requireAssignable(input.assigneeMembershipId(),
                target.getTenantId(), target.getEventId());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        long before = target.getCaseVersion();
        UUID previousAssignee = target.getAssigneeMembershipId();
        target.assign(input.assigneeMembershipId(), now);
        cases.saveAndFlush(target);
        activities.saveAndFlush(new CaseActivity(target, actorId, AdminRole.REVIEWER.name(), "CASE_ASSIGNEE_CHANGED",
                input.reason().trim(), target.getStatus(), target.getStatus(),
                before, target.getCaseVersion(), null, previousAssignee,
                input.assigneeMembershipId(), now));
        CaseDto.View result = CaseDto.View.from(target);
        idempotency.saveAndFlush(new CaseIdempotencyRecord(target.getTenantId(), actorId, "ASSIGN",
                target.getId(), keyDigest, requestDigest, target.getId(),
                json.writeValueAsString(result), now));
        return result;
    }

    private Case reviewerCase(UUID caseId, UUID actorId) {
        Case target = cases.findById(caseId).orElseThrow(CaseWriteService::notFound);
        AdminActor actor = access.requireResourceAccess(
                new AdminResourceScope(target.getTenantId(), target.getEventId()), null, REVIEWER);
        requireSameActor(actor, actorId);
        return target;
    }

    private void requireSameActor(AdminActor actor, UUID actorId) {
        if (!actorId.equals(membershipAccess.actor(actor).getId())) throw notFound();
    }

    private void requireCurrentAssignee(Case target) {
        if (target.getAssigneeMembershipId() == null) throw conflict("CASE_STATE_CONFLICT");
        try {
            membershipAccess.requireAssignable(target.getAssigneeMembershipId(),
                    target.getTenantId(), target.getEventId());
        } catch (CaseApiException stale) {
            throw conflict("CASE_STATE_CONFLICT");
        }
    }

    private static void checkVersion(Case target, Long expected) {
        if (expected == null) throw invalid();
        if (!Objects.equals(target.getCaseVersion(), expected)) throw conflict("CASE_VERSION_CONFLICT");
    }

    private static String combine(String... values) {
        String joined = String.join(" | ", values).trim();
        if (joined.length() > 2000) throw invalid();
        return joined;
    }

    static void validateText(String value, int min, int max) {
        if (value == null || value.trim().length() < min || value.length() > max) throw invalid();
    }

    private static CaseApiException invalid() {
        return new CaseApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED");
    }

    private static CaseApiException notFound() {
        return new CaseApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }

    private static CaseApiException conflict(String code) {
        return new CaseApiException(HttpStatus.CONFLICT, code);
    }
}
