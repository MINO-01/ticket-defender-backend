package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.persistence.ApprovalRequestRepository;
import com.ticket.defender_core.adapter.out.persistence.CaseRepository;
import com.ticket.defender_core.adapter.out.persistence.EvidenceSnapshotRepository;
import com.ticket.defender_core.adapter.out.persistence.ReportVersionRepository;
import com.ticket.defender_core.domain.ApprovalRequest;
import com.ticket.defender_core.domain.Case;
import com.ticket.defender_core.domain.EvidenceSnapshot;
import com.ticket.defender_core.domain.ReportVersion;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 승인 대기 요청의 근거나 요청자 권한이 영구적으로 바뀌었는지 서버가 다시 판정하고, 그런 경우에만 기존
 * {@link ApprovalReviewWriteService#invalidateFromTrustedSystem} 경로로 요청을 닫는다.
 *
 * <p>호출자가 넘긴 오류 코드는 판단 근거로 쓰지 않는다. Case와 요청을 잠근 같은 트랜잭션 안에서 바인딩,
 * 근거, 요청자 권한을 다시 검사하므로, 클라이언트 입력 오류나 이미 결정된 요청이 무효화되지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ApprovalInvalidationWriteService {
    /**
     * 자동 무효화 대상 코드다. 봉인된 행 자체가 바뀐 경우(*_MISMATCH 계열, TEMPLATE_NOT_RETAINED)는 조작 의심이므로
     * 상태를 바꾸지 않고 차단만 유지하며, 일시적인 Hold나 Receipt 문제도 무효화 사유가 아니다.
     */
    static final Set<String> INVALIDATING_CODES = Set.of("EVIDENCE_VERSION_MISMATCH", "SOURCE_CHANGED",
            "SOURCE_NOT_AVAILABLE", "APPROVAL_BASIS_BLOCKED", ApprovalRequesterAccessValidator.REVOKED);

    private final CaseRepository cases;
    private final ApprovalRequestRepository requests;
    private final EvidenceSnapshotRepository snapshots;
    private final ReportVersionRepository reports;
    private final ApprovalReviewBindingValidator bindings;
    private final ApprovalRequestBasisValidator basis;
    private final ApprovalRequesterAccessValidator requesterAccess;
    private final ApprovalReviewWriteService reviewWriter;

    static boolean mayInvalidate(String code) {
        return INVALIDATING_CODES.contains(code);
    }

    /** 무효화했으면 그 사유 코드를, 대상이 아니거나 이미 닫힌 요청이면 빈 값을 돌려준다. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<String> invalidateIfBasisChanged(UUID requestId, UUID actorMembershipId) {
        UUID caseId = requests.findCaseIdById(requestId).orElse(null);
        if (caseId == null) return Optional.empty();
        // 다른 승인 경로와 같은 Case 우선 잠금 순서를 지켜 결정과의 경쟁을 직렬화한다.
        Case target = cases.findByIdForUpdate(caseId).orElse(null);
        ApprovalRequest request = requests.findByIdForUpdate(requestId).orElse(null);
        if (target == null || request == null || request.getStatus() != ApprovalRequest.Status.PENDING
                || !target.getId().equals(request.getCaseId())
                || !target.getTenantId().equals(request.getTenantId())
                || !target.getEventId().equals(request.getEventId())) return Optional.empty();
        EvidenceSnapshot snapshot = snapshots.findById(request.getEvidenceSnapshotId()).orElse(null);
        ReportVersion report = reports.findById(request.getReportVersionId()).orElse(null);
        if (snapshot == null || report == null) return Optional.empty();
        String code = detect(target, request, snapshot, report);
        if (code == null) return Optional.empty();
        reviewWriter.invalidateFromTrustedSystem(requestId, actorMembershipId,
                "AUTOMATIC_INVALIDATION; code=" + code);
        return Optional.of(code);
    }

    private String detect(Case target, ApprovalRequest request, EvidenceSnapshot snapshot, ReportVersion report) {
        try {
            bindings.validatePending(target, request, snapshot, report);
            requesterAccess.requireCurrent(target, request);
            basis.validate(target, snapshot, report, LocalDateTime.now(ZoneOffset.UTC));
            return null;
        } catch (CaseApiException changed) {
            return mayInvalidate(changed.code()) ? changed.code() : null;
        }
    }
}
