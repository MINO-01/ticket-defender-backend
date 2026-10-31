package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.ApprovalReviewDto;
import com.ticket.defender_core.adapter.out.persistence.AdminMembershipRepository;
import com.ticket.defender_core.adapter.out.persistence.ApprovalAuditEventRepository;
import com.ticket.defender_core.adapter.out.persistence.ApprovalDecisionRepository;
import com.ticket.defender_core.adapter.out.persistence.ApprovalRequestRepository;
import com.ticket.defender_core.adapter.out.persistence.CaseActivityRepository;
import com.ticket.defender_core.adapter.out.persistence.CaseIdempotencyRepository;
import com.ticket.defender_core.adapter.out.persistence.CaseRepository;
import com.ticket.defender_core.adapter.out.persistence.EvidenceSnapshotRepository;
import com.ticket.defender_core.adapter.out.persistence.IssuanceHoldRepository;
import com.ticket.defender_core.adapter.out.persistence.MembershipEventGrantRepository;
import com.ticket.defender_core.adapter.out.persistence.ReportVersionRepository;
import com.ticket.defender_core.adapter.out.persistence.ReviewReceiptRepository;
import com.ticket.defender_core.domain.AdminMembershipEntity;
import com.ticket.defender_core.domain.AccessRecordStatus;
import com.ticket.defender_core.domain.ApprovalAuditEvent;
import com.ticket.defender_core.domain.ApprovalDecision;
import com.ticket.defender_core.domain.ApprovalRequest;
import com.ticket.defender_core.domain.Case;
import com.ticket.defender_core.domain.CaseActivity;
import com.ticket.defender_core.domain.CaseIdempotencyRecord;
import com.ticket.defender_core.domain.EvidenceSnapshot;
import com.ticket.defender_core.domain.IssuanceHold;
import com.ticket.defender_core.domain.ReportVersion;
import com.ticket.defender_core.domain.ReviewReceipt;
import com.ticket.defender_core.security.AdminAccessService;
import com.ticket.defender_core.security.AdminResourceScope;
import com.ticket.defender_core.security.AdminRole;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Contains only short, atomic database transactions for W08b approval review commands. */
@Service
@RequiredArgsConstructor
public class ApprovalReviewWriteService {
    private static final Set<AdminRole> APPROVER = Set.of(AdminRole.APPROVER);
    private static final Set<AdminRole> REVIEWER = Set.of(AdminRole.REVIEWER);

    private final AdminAccessService access;
    private final CaseMembershipAccess memberships;
    private final AdminMembershipRepository membershipRepository;
    private final MembershipEventGrantRepository eventGrants;
    private final CaseRepository cases;
    private final ApprovalRequestRepository requests;
    private final EvidenceSnapshotRepository snapshots;
    private final ReportVersionRepository reports;
    private final ReviewReceiptRepository receipts;
    private final ApprovalDecisionRepository decisions;
    private final IssuanceHoldRepository holds;
    private final ApprovalReviewBindingValidator bindings;
    private final ApprovalRequestBasisValidator basis;
    private final ApprovalRequesterAccessValidator requesterAccess;
    private final CaseActivityRepository activities;
    private final ApprovalAuditEventRepository audits;
    private final CaseIdempotencyRepository idempotency;
    private final ObjectMapper json;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReviewReceipt issueReceipt(UUID requestId, UUID memberId, String tokenDigest,
            LocalDateTime now, Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Review receipt TTL must be positive");
        }
        UUID caseId = requests.findCaseIdById(requestId).orElseThrow(ApprovalReviewWriteService::notFound);
        Case target = cases.findByIdForUpdate(caseId).orElseThrow(ApprovalReviewWriteService::notFound);
        ApprovalRequest request = requests.findByIdForUpdate(requestId).orElseThrow(ApprovalReviewWriteService::notFound);
        requireRequestScope(target, request);
        UUID actor = requireApprover(target, memberId);
        Bound bound = bound(target, request);
        bindings.validatePending(target, request, bound.snapshot(), bound.report());
        requesterAccess.requireCurrent(target, request);
        requireNoActiveHold(target);
        basis.validate(target, bound.snapshot(), bound.report(), now);

        for (ReviewReceipt prior : receipts.findByApprovalRequestIdAndApproverMembershipIdAndStatusForUpdate(
                requestId, actor, ReviewReceipt.Status.ISSUED)) {
            String eventType;
            String reason;
            if (prior.isExpiredAt(now)) {
                prior.expire(now);
                eventType = "REVIEW_RECEIPT_EXPIRED";
                reason = "Expired review receipt superseded by a new receipt";
            } else {
                prior.revoke(now);
                eventType = "REVIEW_RECEIPT_REVOKED";
                reason = "Review receipt superseded by a new receipt";
            }
            receipts.saveAndFlush(prior);
            audits.saveAndFlush(ApprovalAuditEvent.reviewReceiptClosed(request, prior, actor, eventType, reason,
                    target.getCaseVersion(), now));
        }
        ReviewReceipt receipt = receipts.saveAndFlush(new ReviewReceipt(request, actor, tokenDigest, now,
                now.plus(ttl)));
        long caseVersion = target.getCaseVersion();
        activities.saveAndFlush(new CaseActivity(target, actor, AdminRole.APPROVER.name(),
                "REVIEW_RECEIPT_ISSUED", "approvalRequest=" + request.getId()
                        + "; reviewReceipt=" + receipt.getId(),
                target.getStatus(), target.getStatus(), caseVersion, caseVersion, null, now));
        audits.saveAndFlush(ApprovalAuditEvent.reviewReceiptIssued(request, receipt, actor, caseVersion, now));
        return receipt;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ApprovalDecision decide(UUID requestId, ApprovalReviewDto.DecisionInput input, UUID memberId,
            String tokenDigest, String keyDigest, String requestDigest) {
        UUID caseId = requests.findCaseIdById(requestId).orElseThrow(ApprovalReviewWriteService::notFound);
        Case target = cases.findByIdForUpdate(caseId).orElseThrow(ApprovalReviewWriteService::notFound);
        ApprovalRequest request = requests.findByIdForUpdate(requestId).orElseThrow(ApprovalReviewWriteService::notFound);
        requireRequestScope(target, request);
        UUID actor = requireApprover(target, memberId);
        rejectSelfApproval(target, request, actor);
        Bound bound = bound(target, request);
        bindings.validatePending(target, request, bound.snapshot(), bound.report());
        expected(target, request, bound.report(), input.expectedCaseVersion(), input.expectedRequestVersion(),
                input.expectedReportVersion());
        if (!request.getEvidenceSnapshotId().equals(input.evidenceSnapshotId())
                || !request.getManifestSha256().equals(input.manifestSha256())
                || !request.getReportContentSha256().equals(input.reportContentSha256())) {
            throw conflict("APPROVAL_REVIEW_MISMATCH");
        }
        requesterAccess.requireCurrent(target, request);
        requireNoActiveHold(target);
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        basis.validate(target, bound.snapshot(), bound.report(), now);
        ReviewReceipt receipt = receipt(tokenDigest, request, actor, now);
        if (decisions.findByApprovalRequestId(request.getId()).isPresent()) {
            throw conflict("APPROVAL_REQUEST_CLOSED");
        }

        long caseVersionBefore = target.getCaseVersion();
        long requestVersionBefore = request.getVersion();
        long reportVersionBefore = bound.report().getVersion();
        String fromStatus = request.getStatus().name();
        Case.Status fromCase = target.getStatus();
        if (input.outcome() == ApprovalDecision.Outcome.APPROVE) {
            request.approve();
            bound.report().approve();
            target.approveForReport(bound.report().getId(), now);
        } else {
            request.reject();
            bound.report().reject();
            target.returnToReview(bound.report().getId(), now);
        }
        receipt.consume(now);
        cases.saveAndFlush(target);
        reports.saveAndFlush(bound.report());
        requests.saveAndFlush(request);
        receipts.saveAndFlush(receipt);
        revokeOutstanding(request, actor, target.getCaseVersion(), now);
        ApprovalDecision decision = decisions.saveAndFlush(new ApprovalDecision(request, receipt, target, actor,
                input.outcome(), input.reason().trim(), caseVersionBefore, requestVersionBefore,
                reportVersionBefore, now));
        activities.saveAndFlush(new CaseActivity(target, actor, AdminRole.APPROVER.name(), "APPROVAL_DECIDED",
                "approvalRequest=" + request.getId() + "; approvalDecision=" + decision.getId()
                        + "; outcome=" + input.outcome().name() + "; " + input.reason().trim(),
                fromCase, target.getStatus(), caseVersionBefore, target.getCaseVersion(), null, now));
        audits.saveAndFlush(ApprovalAuditEvent.decision(request, decision, actor, fromStatus,
                caseVersionBefore, target.getCaseVersion(), now));
        idempotency.saveAndFlush(new CaseIdempotencyRecord(target.getTenantId(), actor,
                ApprovalReviewService.DECISION_OPERATION, target.getId(), keyDigest, requestDigest,
                target.getId(), json.writeValueAsString(Map.of("approvalDecisionId", decision.getId())), now));
        return decision;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ApprovalRequest withdraw(UUID requestId, ApprovalReviewDto.WithdrawInput input, UUID memberId,
            String keyDigest, String requestDigest) {
        UUID caseId = requests.findCaseIdById(requestId).orElseThrow(ApprovalReviewWriteService::notFound);
        Case target = cases.findByIdForUpdate(caseId).orElseThrow(ApprovalReviewWriteService::notFound);
        ApprovalRequest request = requests.findByIdForUpdate(requestId).orElseThrow(ApprovalReviewWriteService::notFound);
        requireRequestScope(target, request);
        UUID actor = requireReviewer(target, memberId);
        Bound bound = bound(target, request);
        bindings.validatePending(target, request, bound.snapshot(), bound.report());
        expected(target, request, bound.report(), input.expectedCaseVersion(), input.expectedRequestVersion(),
                input.expectedReportVersion());

        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        long caseVersionBefore = target.getCaseVersion();
        String fromStatus = request.getStatus().name();
        request.withdraw();
        bound.report().withdraw();
        target.returnToReview(bound.report().getId(), now);
        cases.saveAndFlush(target);
        reports.saveAndFlush(bound.report());
        requests.saveAndFlush(request);
        revokeOutstanding(request, actor, target.getCaseVersion(), now);
        activities.saveAndFlush(new CaseActivity(target, actor, AdminRole.REVIEWER.name(), "APPROVAL_WITHDRAWN",
                "approvalRequest=" + request.getId() + "; " + input.reason().trim(),
                Case.Status.READY_FOR_APPROVAL, Case.Status.IN_REVIEW, caseVersionBefore,
                target.getCaseVersion(), null, now));
        audits.saveAndFlush(ApprovalAuditEvent.withdrawn(request, actor, fromStatus, caseVersionBefore,
                target.getCaseVersion(), input.reason().trim(), now));
        idempotency.saveAndFlush(new CaseIdempotencyRecord(target.getTenantId(), actor,
                ApprovalReviewService.WITHDRAW_OPERATION, target.getId(), keyDigest, requestDigest,
                target.getId(), json.writeValueAsString(Map.of("approvalRequestId", request.getId())), now));
        return request;
    }

    /**
     * 서버 내부 전용 무효화 경로이며 웹 API로 노출하지 않는다. 현재는 승인 처리 중 근거 변경을 재확인한
     * {@link ApprovalInvalidationWriteService}가 호출하며, 다른 내부 호출자도 실행 주체 멤버십을 직접 지정해야 한다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ApprovalRequest invalidateFromTrustedSystem(UUID requestId, UUID actorMembershipId, String reason) {
        UUID caseId = requests.findCaseIdById(requestId).orElseThrow(ApprovalReviewWriteService::notFound);
        Case target = cases.findByIdForUpdate(caseId).orElseThrow(ApprovalReviewWriteService::notFound);
        ApprovalRequest request = requests.findByIdForUpdate(requestId).orElseThrow(ApprovalReviewWriteService::notFound);
        requireRequestScope(target, request);
        AdminMembershipEntity actor = membershipRepository.findById(actorMembershipId)
                .filter(member -> target.getTenantId().equals(member.getTenantId())
                        && AccessRecordStatus.ACTIVE.name().equals(member.getStatus()))
                .orElseThrow(ApprovalReviewWriteService::notFound);
        if (eventGrants.findAllByMembershipIdAndTenantId(actor.getId(), target.getTenantId()).stream()
                .noneMatch(grant -> target.getEventId().equals(grant.getEventId()))) throw notFound();
        Bound bound = bound(target, request);
        bindings.validatePendingLifecycle(target, request, bound.snapshot(), bound.report());
        CasePreparationWriteService.validate(reason, 3, 1800);

        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        long caseVersionBefore = target.getCaseVersion();
        String fromStatus = request.getStatus().name();
        request.invalidate();
        bound.report().invalidate();
        target.returnToReview(bound.report().getId(), now);
        cases.saveAndFlush(target);
        reports.saveAndFlush(bound.report());
        requests.saveAndFlush(request);
        revokeOutstanding(request, actor.getId(), target.getCaseVersion(), now);
        activities.saveAndFlush(new CaseActivity(target, actor.getId(), "SYSTEM", "APPROVAL_INVALIDATED",
                "approvalRequest=" + request.getId() + "; " + reason.trim(),
                Case.Status.READY_FOR_APPROVAL, Case.Status.IN_REVIEW, caseVersionBefore,
                target.getCaseVersion(), null, now));
        audits.saveAndFlush(ApprovalAuditEvent.invalidated(request, actor.getId(), fromStatus,
                caseVersionBefore, target.getCaseVersion(), reason.trim(), now));
        return request;
    }

    private UUID requireApprover(Case target, UUID expectedMember) {
        var actor = access.requireResourceAccess(new AdminResourceScope(target.getTenantId(), target.getEventId()),
                null, APPROVER);
        UUID member = memberships.actor(actor).getId();
        if (!member.equals(expectedMember)) throw notFound();
        return member;
    }

    private UUID requireReviewer(Case target, UUID expectedMember) {
        var actor = access.requireResourceAccess(new AdminResourceScope(target.getTenantId(), target.getEventId()),
                null, REVIEWER);
        UUID member = memberships.actor(actor).getId();
        if (!member.equals(expectedMember)) throw notFound();
        return member;
    }

    private void rejectSelfApproval(Case target, ApprovalRequest request, UUID approverMembershipId) {
        AdminMembershipEntity requester = membershipRepository.findById(request.getRequestedByMembershipId())
                .filter(member -> target.getTenantId().equals(member.getTenantId()))
                .orElseThrow(ApprovalReviewWriteService::notFound);
        AdminMembershipEntity approver = membershipRepository.findById(approverMembershipId)
                .filter(member -> target.getTenantId().equals(member.getTenantId()))
                .orElseThrow(ApprovalReviewWriteService::notFound);
        if (Objects.equals(requester.getSubject(), approver.getSubject())) {
            throw new CaseApiException(HttpStatus.FORBIDDEN, "SELF_APPROVAL_FORBIDDEN");
        }
    }

    private static void requireRequestScope(Case target, ApprovalRequest request) {
        if (!target.getId().equals(request.getCaseId())
                || !target.getTenantId().equals(request.getTenantId())
                || !target.getEventId().equals(request.getEventId())) {
            throw notFound();
        }
    }

    private Bound bound(Case target, ApprovalRequest request) {
        EvidenceSnapshot snapshot = snapshots.findById(request.getEvidenceSnapshotId())
                .orElseThrow(ApprovalReviewWriteService::notFound);
        ReportVersion report = reports.findById(request.getReportVersionId())
                .orElseThrow(ApprovalReviewWriteService::notFound);
        return new Bound(snapshot, report);
    }

    private ReviewReceipt receipt(String tokenDigest, ApprovalRequest request, UUID actor,
            LocalDateTime now) {
        ReviewReceipt receipt = receipts.findByTokenSha256ForUpdate(tokenDigest)
                .orElseThrow(() -> new CaseApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "REVIEW_RECEIPT_INVALID"));
        if (!sameReceiptBinding(receipt, request, actor)) {
            throw new CaseApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REVIEW_RECEIPT_INVALID");
        }
        if (receipt.getStatus() != ReviewReceipt.Status.ISSUED) {
            throw conflict("REVIEW_RECEIPT_ALREADY_USED");
        }
        if (receipt.isExpiredAt(now)) {
            throw new CaseApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REVIEW_RECEIPT_EXPIRED");
        }
        return receipt;
    }

    private static boolean sameReceiptBinding(ReviewReceipt receipt, ApprovalRequest request, UUID actor) {
        return actor.equals(receipt.getApproverMembershipId())
                && request.getTenantId().equals(receipt.getTenantId())
                && request.getEventId().equals(receipt.getEventId())
                && request.getCaseId().equals(receipt.getCaseId())
                && request.getId().equals(receipt.getApprovalRequestId())
                && request.getReportVersionId().equals(receipt.getReportVersionId())
                && request.getReportRevision() == receipt.getReportRevision()
                && request.getReportContentSha256().equals(receipt.getReportContentSha256())
                && request.getReportTemplateVersion().equals(receipt.getReportTemplateVersion())
                && request.getReportTemplateSha256().equals(receipt.getReportTemplateSha256())
                && request.getEvidenceSnapshotId().equals(receipt.getEvidenceSnapshotId())
                && request.getEvidenceRevision() == receipt.getEvidenceRevision()
                && request.getManifestSha256().equals(receipt.getManifestSha256());
    }

    private void revokeOutstanding(ApprovalRequest request, UUID actor, long caseVersion, LocalDateTime now) {
        for (ReviewReceipt outstanding : receipts.findByApprovalRequestIdAndStatusForUpdate(
                request.getId(), ReviewReceipt.Status.ISSUED)) {
            String eventType;
            String reason;
            if (outstanding.isExpiredAt(now)) {
                outstanding.expire(now);
                eventType = "REVIEW_RECEIPT_EXPIRED";
                reason = "Expired review receipt closed with the approval request";
            } else {
                outstanding.revoke(now);
                eventType = "REVIEW_RECEIPT_REVOKED";
                reason = "Review receipt closed with the approval request";
            }
            receipts.saveAndFlush(outstanding);
            audits.saveAndFlush(ApprovalAuditEvent.reviewReceiptClosed(request, outstanding, actor, eventType,
                    reason, caseVersion, now));
        }
    }

    private void requireNoActiveHold(Case target) {
        if (holds.existsByTenantIdAndEventIdAndCaseIdAndStatus(target.getTenantId(), target.getEventId(),
                target.getId(), IssuanceHold.Status.ACTIVE)) {
            throw conflict("ISSUANCE_HOLD_ACTIVE");
        }
    }

    private static void expected(Case target, ApprovalRequest request, ReportVersion report,
            Long expectedCaseVersion, Long expectedRequestVersion, Long expectedReportVersion) {
        if (!Objects.equals(expectedCaseVersion, target.getCaseVersion())) {
            throw conflict("CASE_VERSION_CONFLICT");
        }
        if (!Objects.equals(expectedRequestVersion, request.getVersion())) {
            throw conflict("REQUEST_VERSION_CONFLICT");
        }
        if (!Objects.equals(expectedReportVersion, report.getVersion())) {
            throw conflict("REPORT_VERSION_CONFLICT");
        }
    }

    private static CaseApiException notFound() {
        return new CaseApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }

    private static CaseApiException conflict(String code) {
        return new CaseApiException(HttpStatus.CONFLICT, code);
    }

    private record Bound(EvidenceSnapshot snapshot, ReportVersion report) { }
}
