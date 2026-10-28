package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.ApprovalRequestDto;
import com.ticket.defender_core.adapter.out.persistence.ApprovalRequestRepository;
import com.ticket.defender_core.adapter.out.persistence.CaseIdempotencyRepository;
import com.ticket.defender_core.adapter.out.persistence.CaseRepository;
import com.ticket.defender_core.domain.ApprovalRequest;
import com.ticket.defender_core.domain.Case;
import com.ticket.defender_core.domain.CaseIdempotencyRecord;
import com.ticket.defender_core.security.AdminAccessService;
import com.ticket.defender_core.security.AdminResourceScope;
import com.ticket.defender_core.security.AdminRole;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ApprovalRequestService {
    static final String OPERATION = "APPROVAL_REQUEST_CREATE";
    private static final Pattern KEY = Pattern.compile(
            "(?i)[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<AdminRole> REVIEWER = Set.of(AdminRole.REVIEWER);
    private static final Set<AdminRole> VIEW_ROLES = Set.of(
            AdminRole.TENANT_ADMIN, AdminRole.REVIEWER, AdminRole.APPROVER);

    private final AdminAccessService access;
    private final CaseMembershipAccess memberships;
    private final CaseRepository cases;
    private final ApprovalRequestRepository requests;
    private final CaseIdempotencyRepository idempotency;
    private final ApprovalRequestWriteService writer;
    private final ObjectMapper json;

    public ApprovalRequestDto.View submit(UUID caseId, ApprovalRequestDto.Submit input, String key) {
        validate(input);
        Authorized actor = reviewer(caseId);
        Prepared prepared = prepare(key, input);
        ApprovalRequest prior = replay(caseId, actor, prepared);
        if (prior != null) return ApprovalRequestDto.View.from(prior);
        try {
            return ApprovalRequestDto.View.from(writer.submit(caseId, input, actor.memberId(),
                    prepared.keyDigest(), prepared.requestDigest()));
        } catch (CaseApiException collision) {
            ApprovalRequest committed = replay(caseId, reviewer(caseId), prepared);
            if (committed != null) return ApprovalRequestDto.View.from(committed);
            throw collision;
        } catch (OptimisticLockingFailureException collision) {
            ApprovalRequest committed = replay(caseId, reviewer(caseId), prepared);
            if (committed != null) return ApprovalRequestDto.View.from(committed);
            throw conflict("CASE_VERSION_CONFLICT");
        } catch (DataIntegrityViolationException collision) {
            ApprovalRequest committed = replay(caseId, reviewer(caseId), prepared);
            if (committed != null) return ApprovalRequestDto.View.from(committed);
            if (requests.findByTenantIdAndCaseIdAndStatus(
                    actor.tenantId(), caseId, ApprovalRequest.Status.PENDING).isPresent())
                throw conflict("APPROVAL_REQUEST_ALREADY_PENDING");
            if (requests.findByReportVersionId(input.reportVersionId()).isPresent())
                throw conflict("REPORT_REVISION_CLOSED");
            throw collision;
        }
    }

    @Transactional(readOnly = true)
    public ApprovalRequestDto.View detail(UUID approvalRequestId) {
        ApprovalRequest request = requests.findById(approvalRequestId)
                .orElseThrow(ApprovalRequestService::notFound);
        access.requireResourceAccess(new AdminResourceScope(request.getTenantId(), request.getEventId()),
                null, VIEW_ROLES);
        Case target = cases.findById(request.getCaseId()).orElseThrow(ApprovalRequestService::notFound);
        if (!request.getTenantId().equals(target.getTenantId())
                || !request.getEventId().equals(target.getEventId())) throw notFound();
        return ApprovalRequestDto.View.from(request);
    }

    private Authorized reviewer(UUID caseId) {
        Case target = cases.findById(caseId).orElseThrow(ApprovalRequestService::notFound);
        var actor = access.requireResourceAccess(
                new AdminResourceScope(target.getTenantId(), target.getEventId()), null, REVIEWER);
        return new Authorized(target.getTenantId(), target.getEventId(), memberships.actor(actor).getId());
    }

    private ApprovalRequest replay(UUID caseId, Authorized actor, Prepared prepared) {
        CaseIdempotencyRecord record = idempotency
                .findByTenantIdAndActorMembershipIdAndOperationAndTargetCaseIdAndKeyDigest(
                        actor.tenantId(), actor.memberId(), OPERATION, caseId, prepared.keyDigest())
                .orElse(null);
        if (record == null) return null;
        if (!record.getRequestDigest().equals(prepared.requestDigest()))
            throw conflict("IDEMPOTENCY_KEY_REUSED");
        UUID requestId = json.readValue(record.getResponseJson(), Reference.class).approvalRequestId();
        ApprovalRequest request = requests.findById(requestId).orElseThrow(ApprovalRequestService::notFound);
        if (!caseId.equals(request.getCaseId()) || !actor.tenantId().equals(request.getTenantId())
                || !actor.eventId().equals(request.getEventId())) throw notFound();
        return request;
    }

    private Prepared prepare(String key, ApprovalRequestDto.Submit input) {
        if (key == null || !KEY.matcher(key).matches())
            throw new CaseApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY");
        return new Prepared(CaseSnapshotManifest.sha256(key.toLowerCase()),
                CaseSnapshotManifest.sha256(json.writeValueAsString(input)));
    }

    private static void validate(ApprovalRequestDto.Submit input) {
        if (input == null || input.reportVersionId() == null || input.evidenceSnapshotId() == null
                || input.expectedCaseVersion() == null || input.expectedReportVersion() == null
                || input.expectedCaseVersion() < 0 || input.expectedReportVersion() < 0
                || input.expectedReportContentSha256() == null
                || !SHA256.matcher(input.expectedReportContentSha256()).matches()) throw invalid();
        CasePreparationWriteService.validate(input.reason(), 3, 1800);
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

    private record Authorized(UUID tenantId, UUID eventId, UUID memberId) { }
    private record Prepared(String keyDigest, String requestDigest) { }
    private record Reference(UUID approvalRequestId) { }
}
