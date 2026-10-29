package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.ApprovalReviewDto;
import com.ticket.defender_core.adapter.out.persistence.ApprovalDecisionRepository;
import com.ticket.defender_core.adapter.out.persistence.ApprovalRequestRepository;
import com.ticket.defender_core.adapter.out.persistence.CaseIdempotencyRepository;
import com.ticket.defender_core.adapter.out.persistence.CaseRepository;
import com.ticket.defender_core.adapter.out.persistence.EvidenceSnapshotRepository;
import com.ticket.defender_core.adapter.out.persistence.ReportVersionRepository;
import com.ticket.defender_core.domain.ApprovalDecision;
import com.ticket.defender_core.domain.ApprovalRequest;
import com.ticket.defender_core.domain.Case;
import com.ticket.defender_core.domain.CaseIdempotencyRecord;
import com.ticket.defender_core.domain.EvidenceSnapshot;
import com.ticket.defender_core.domain.ReportVersion;
import com.ticket.defender_core.security.AdminAccessService;
import com.ticket.defender_core.security.AdminResourceScope;
import com.ticket.defender_core.security.AdminRole;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ApprovalReviewService {
    static final String DECISION_OPERATION = "APPROVAL_DECISION";
    static final String WITHDRAW_OPERATION = "APPROVAL_REQUEST_WITHDRAW";
    private static final Pattern KEY = Pattern.compile(
            "(?i)[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<AdminRole> APPROVER = Set.of(AdminRole.APPROVER);
    private static final Set<AdminRole> REVIEWER = Set.of(AdminRole.REVIEWER);
    private static final SecureRandom TOKENS = new SecureRandom();

    private final AdminAccessService access;
    private final CaseMembershipAccess memberships;
    private final ApprovalRequestRepository requests;
    private final CaseRepository cases;
    private final EvidenceSnapshotRepository snapshots;
    private final ReportVersionRepository reports;
    private final ApprovalDecisionRepository decisions;
    private final CaseIdempotencyRepository idempotency;
    private final ApprovalReviewBindingValidator bindings;
    private final ApprovalReviewWriteService writer;
    private final ObjectMapper json;
    @Value("${ticket-defender.approval.review-receipt-ttl:PT15M}")
    private Duration reviewReceiptTtl;

    @Transactional(readOnly = true)
    public ApprovalReviewDto.ReviewPackage reviewPackage(UUID requestId) {
        ApprovalRequest request = request(requestId);
        approver(request);
        Bound bound = bound(request);
        bindings.validatePending(bound.target(), request, bound.snapshot(), bound.report());
        return ApprovalReviewDto.ReviewPackage.from(request, bound.target(), bound.snapshot(), bound.report());
    }

    public ApprovalReviewDto.Receipt issueReceipt(UUID requestId) {
        ApprovalRequest request = request(requestId);
        Authorized actor = approver(request);
        String token = token();
        com.ticket.defender_core.domain.ReviewReceipt receipt = writer.issueReceipt(requestId, actor.memberId(),
                CaseSnapshotManifest.sha256(token), LocalDateTime.now(ZoneOffset.UTC), reviewReceiptTtl);
        return ApprovalReviewDto.Receipt.from(receipt, token);
    }

    public ApprovalReviewDto.DecisionView decide(UUID requestId, ApprovalReviewDto.DecisionInput input,
            String key) {
        validateDecision(input);
        ApprovalRequest request = request(requestId);
        Authorized actor = approver(request);
        Prepared prepared = prepareDecision(key, input);
        ApprovalDecision prior = replayDecision(request, actor, prepared);
        if (prior != null) return view(prior);
        try {
            ApprovalDecision result = writer.decide(requestId, input, actor.memberId(), prepared.tokenDigest(),
                    prepared.keyDigest(), prepared.requestDigest());
            return view(result);
        } catch (CaseApiException collision) {
            ApprovalDecision committed = replayDecision(request, approver(request(requestId)), prepared);
            if (committed != null) return view(committed);
            throw collision;
        } catch (OptimisticLockingFailureException collision) {
            ApprovalDecision committed = replayDecision(request, approver(request(requestId)), prepared);
            if (committed != null) return view(committed);
            throw conflict("CASE_VERSION_CONFLICT");
        } catch (DataIntegrityViolationException collision) {
            ApprovalDecision committed = replayDecision(request, approver(request(requestId)), prepared);
            if (committed != null) return view(committed);
            throw conflict("APPROVAL_REQUEST_CLOSED");
        }
    }

    public ApprovalReviewDto.WithdrawView withdraw(UUID requestId, ApprovalReviewDto.WithdrawInput input,
            String key) {
        validateWithdraw(input);
        ApprovalRequest request = request(requestId);
        Authorized actor = reviewer(request);
        Prepared prepared = prepare(key, input);
        ApprovalRequest prior = replayWithdraw(request, actor, prepared);
        if (prior != null) return withdrawView(prior);
        try {
            ApprovalRequest result = writer.withdraw(requestId, input, actor.memberId(),
                    prepared.keyDigest(), prepared.requestDigest());
            return withdrawView(result);
        } catch (CaseApiException collision) {
            ApprovalRequest committed = replayWithdraw(request, reviewer(request(requestId)), prepared);
            if (committed != null) return withdrawView(committed);
            throw collision;
        } catch (OptimisticLockingFailureException collision) {
            ApprovalRequest committed = replayWithdraw(request, reviewer(request(requestId)), prepared);
            if (committed != null) return withdrawView(committed);
            throw conflict("CASE_VERSION_CONFLICT");
        } catch (DataIntegrityViolationException collision) {
            ApprovalRequest committed = replayWithdraw(request, reviewer(request(requestId)), prepared);
            if (committed != null) return withdrawView(committed);
            throw conflict("APPROVAL_REQUEST_CLOSED");
        }
    }

    private ApprovalReviewDto.DecisionView view(ApprovalDecision decision) {
        ApprovalRequest request = requests.findById(decision.getApprovalRequestId())
                .orElseThrow(ApprovalReviewService::notFound);
        Bound bound = bound(request);
        return ApprovalReviewDto.DecisionView.from(decision, request, bound.target(), bound.report());
    }

    private ApprovalReviewDto.WithdrawView withdrawView(ApprovalRequest request) {
        Bound bound = bound(request);
        return ApprovalReviewDto.WithdrawView.from(request, bound.target(), bound.report());
    }

    private ApprovalDecision replayDecision(ApprovalRequest request, Authorized actor, Prepared prepared) {
        CaseIdempotencyRecord record = idempotency
                .findByTenantIdAndActorMembershipIdAndOperationAndTargetCaseIdAndKeyDigest(
                        actor.tenantId(), actor.memberId(), DECISION_OPERATION, request.getCaseId(),
                        prepared.keyDigest())
                .orElse(null);
        if (record == null) return null;
        if (!record.getRequestDigest().equals(prepared.requestDigest())) {
            throw conflict("IDEMPOTENCY_KEY_REUSED");
        }
        UUID decisionId = json.readValue(record.getResponseJson(), DecisionReference.class).approvalDecisionId();
        ApprovalDecision decision = decisions.findById(decisionId).orElseThrow(ApprovalReviewService::notFound);
        if (!request.getId().equals(decision.getApprovalRequestId())
                || !actor.tenantId().equals(decision.getTenantId())
                || !actor.eventId().equals(decision.getEventId())) throw notFound();
        return decision;
    }

    private ApprovalRequest replayWithdraw(ApprovalRequest request, Authorized actor, Prepared prepared) {
        CaseIdempotencyRecord record = idempotency
                .findByTenantIdAndActorMembershipIdAndOperationAndTargetCaseIdAndKeyDigest(
                        actor.tenantId(), actor.memberId(), WITHDRAW_OPERATION, request.getCaseId(),
                        prepared.keyDigest())
                .orElse(null);
        if (record == null) return null;
        if (!record.getRequestDigest().equals(prepared.requestDigest())) {
            throw conflict("IDEMPOTENCY_KEY_REUSED");
        }
        UUID requestId = json.readValue(record.getResponseJson(), RequestReference.class).approvalRequestId();
        ApprovalRequest result = requests.findById(requestId).orElseThrow(ApprovalReviewService::notFound);
        if (!request.getId().equals(result.getId()) || !actor.tenantId().equals(result.getTenantId())
                || !actor.eventId().equals(result.getEventId())) throw notFound();
        return result;
    }

    private Authorized approver(ApprovalRequest request) {
        var actor = access.requireResourceAccess(new AdminResourceScope(request.getTenantId(), request.getEventId()),
                null, APPROVER);
        return new Authorized(actor.tenantId(), actor.eventId(), memberships.actor(actor).getId());
    }

    private Authorized reviewer(ApprovalRequest request) {
        var actor = access.requireResourceAccess(new AdminResourceScope(request.getTenantId(), request.getEventId()),
                null, REVIEWER);
        return new Authorized(actor.tenantId(), actor.eventId(), memberships.actor(actor).getId());
    }

    private Bound bound(ApprovalRequest request) {
        Case target = cases.findById(request.getCaseId()).orElseThrow(ApprovalReviewService::notFound);
        EvidenceSnapshot snapshot = snapshots.findById(request.getEvidenceSnapshotId())
                .orElseThrow(ApprovalReviewService::notFound);
        ReportVersion report = reports.findById(request.getReportVersionId())
                .orElseThrow(ApprovalReviewService::notFound);
        return new Bound(target, snapshot, report);
    }

    private ApprovalRequest request(UUID requestId) {
        return requests.findById(requestId).orElseThrow(ApprovalReviewService::notFound);
    }

    private Prepared prepareDecision(String key, ApprovalReviewDto.DecisionInput input) {
        Prepared keyPrepared = prepare(key, new DecisionFingerprint(input.outcome(), input.reason(),
                input.evidenceSnapshotId(), input.manifestSha256(), input.reportContentSha256(),
                CaseSnapshotManifest.sha256(input.reviewReceiptToken()), input.reviewConfirmed(),
                input.expectedCaseVersion(), input.expectedRequestVersion(), input.expectedReportVersion()));
        return new Prepared(keyPrepared.keyDigest(), keyPrepared.requestDigest(),
                CaseSnapshotManifest.sha256(input.reviewReceiptToken()));
    }

    private Prepared prepare(String key, Object input) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new CaseApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY");
        }
        return new Prepared(CaseSnapshotManifest.sha256(key.toLowerCase()),
                CaseSnapshotManifest.sha256(json.writeValueAsString(input)), null);
    }

    private static String token() {
        byte[] bytes = new byte[32];
        TOKENS.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void validateDecision(ApprovalReviewDto.DecisionInput input) {
        if (input == null || input.outcome() == null || input.evidenceSnapshotId() == null
                || input.manifestSha256() == null || !SHA256.matcher(input.manifestSha256()).matches()
                || input.reportContentSha256() == null || !SHA256.matcher(input.reportContentSha256()).matches()
                || input.reviewReceiptToken() == null || input.reviewReceiptToken().length() < 32
                || input.reviewReceiptToken().length() > 512 || !Boolean.TRUE.equals(input.reviewConfirmed())
                || input.expectedCaseVersion() == null || input.expectedRequestVersion() == null
                || input.expectedReportVersion() == null || input.expectedCaseVersion() < 0
                || input.expectedRequestVersion() < 0 || input.expectedReportVersion() < 0) {
            throw invalid();
        }
        CasePreparationWriteService.validate(input.reason(), 3, 1800);
    }

    private static void validateWithdraw(ApprovalReviewDto.WithdrawInput input) {
        if (input == null || input.expectedCaseVersion() == null || input.expectedRequestVersion() == null
                || input.expectedReportVersion() == null || input.expectedCaseVersion() < 0
                || input.expectedRequestVersion() < 0 || input.expectedReportVersion() < 0) {
            throw invalid();
        }
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
    private record Bound(Case target, EvidenceSnapshot snapshot, ReportVersion report) { }
    private record Prepared(String keyDigest, String requestDigest, String tokenDigest) { }
    private record DecisionReference(UUID approvalDecisionId) { }
    private record RequestReference(UUID approvalRequestId) { }
    private record DecisionFingerprint(ApprovalDecision.Outcome outcome, String reason,
                                       UUID evidenceSnapshotId, String manifestSha256,
                                       String reportContentSha256, String reviewReceiptTokenSha256,
                                       Boolean reviewConfirmed, Long expectedCaseVersion,
                                       Long expectedRequestVersion, Long expectedReportVersion) { }
}
