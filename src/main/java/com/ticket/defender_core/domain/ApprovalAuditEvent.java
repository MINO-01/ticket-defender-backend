package com.ticket.defender_core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "approval_audit_events", indexes = @Index(name = "idx_approval_audit_scope_time",
        columnList = "tenant_id,event_id,case_id,occurred_at"))
public class ApprovalAuditEvent {
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "id", length = 36, nullable = false)
    private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "tenant_id", length = 36, nullable = false)
    private UUID tenantId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "event_id", length = 36, nullable = false)
    private UUID eventId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "case_id", length = 36, nullable = false)
    private UUID caseId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "approval_request_id", length = 36, nullable = false)
    private UUID approvalRequestId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "review_receipt_id", length = 36)
    private UUID reviewReceiptId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "approval_decision_id", length = 36)
    private UUID approvalDecisionId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "report_version_id", length = 36, nullable = false)
    private UUID reportVersionId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "evidence_snapshot_id", length = 36, nullable = false)
    private UUID evidenceSnapshotId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "actor_membership_id", length = 36, nullable = false)
    private UUID actorMembershipId;
    @Column(name = "event_type", length = 48, nullable = false)
    private String eventType;
    @Column(name = "from_status", length = 24)
    private String fromStatus;
    @Column(name = "to_status", length = 24, nullable = false)
    private String toStatus;
    @Column(name = "report_content_sha256", length = 64, nullable = false)
    private String reportContentSha256;
    @Column(name = "manifest_sha256", length = 64, nullable = false)
    private String manifestSha256;
    @Column(name = "evidence_revision", nullable = false)
    private long evidenceRevision;
    @Column(name = "case_version_before", nullable = false)
    private long caseVersionBefore;
    @Column(name = "case_version_after", nullable = false)
    private long caseVersionAfter;
    @Column(name = "reason", length = 2000, nullable = false)
    private String reason;
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    public ApprovalAuditEvent(ApprovalRequest request, UUID actorMembershipId,
            long caseVersionBefore, LocalDateTime occurredAt) {
        this(request, actorMembershipId, "APPROVAL_REQUESTED", null, request.getStatus().name(),
                caseVersionBefore, request.getCaseVersionAtSubmit(), request.getReason(), null, null,
                occurredAt);
    }

    public static ApprovalAuditEvent reviewReceiptIssued(ApprovalRequest request, ReviewReceipt receipt,
            UUID actorMembershipId, long caseVersion, LocalDateTime occurredAt) {
        return new ApprovalAuditEvent(request, actorMembershipId, "REVIEW_RECEIPT_ISSUED",
                request.getStatus().name(), request.getStatus().name(), caseVersion, caseVersion,
                "Review receipt issued", receipt.getId(), null, occurredAt);
    }

    public static ApprovalAuditEvent reviewReceiptClosed(ApprovalRequest request, ReviewReceipt receipt,
            UUID actorMembershipId, String eventType, String reason, long caseVersion,
            LocalDateTime occurredAt) {
        return new ApprovalAuditEvent(request, actorMembershipId, eventType, request.getStatus().name(),
                request.getStatus().name(), caseVersion, caseVersion, reason, receipt.getId(), null,
                occurredAt);
    }

    public static ApprovalAuditEvent decision(ApprovalRequest request, ApprovalDecision decision,
            UUID actorMembershipId, String fromStatus, long caseVersionBefore,
            long caseVersionAfter, LocalDateTime occurredAt) {
        return new ApprovalAuditEvent(request, actorMembershipId, "APPROVAL_DECIDED", fromStatus,
                request.getStatus().name(), caseVersionBefore, caseVersionAfter, decision.getReason(),
                decision.getReviewReceiptId(), decision.getId(), occurredAt);
    }

    public static ApprovalAuditEvent withdrawn(ApprovalRequest request, UUID actorMembershipId,
            String fromStatus, long caseVersionBefore, long caseVersionAfter, String reason,
            LocalDateTime occurredAt) {
        return new ApprovalAuditEvent(request, actorMembershipId, "APPROVAL_WITHDRAWN", fromStatus,
                request.getStatus().name(), caseVersionBefore, caseVersionAfter, reason, null, null,
                occurredAt);
    }

    public static ApprovalAuditEvent invalidated(ApprovalRequest request, UUID actorMembershipId,
            String fromStatus, long caseVersionBefore, long caseVersionAfter, String reason,
            LocalDateTime occurredAt) {
        return new ApprovalAuditEvent(request, actorMembershipId, "APPROVAL_INVALIDATED", fromStatus,
                request.getStatus().name(), caseVersionBefore, caseVersionAfter, reason, null, null,
                occurredAt);
    }

    private ApprovalAuditEvent(ApprovalRequest request, UUID actorMembershipId, String eventType,
            String fromStatus, String toStatus, long caseVersionBefore, long caseVersionAfter,
            String reason, UUID reviewReceiptId, UUID approvalDecisionId, LocalDateTime occurredAt) {
        this.id = UUID.randomUUID();
        this.tenantId = request.getTenantId();
        this.eventId = request.getEventId();
        this.caseId = request.getCaseId();
        this.approvalRequestId = request.getId();
        this.reportVersionId = request.getReportVersionId();
        this.evidenceSnapshotId = request.getEvidenceSnapshotId();
        this.actorMembershipId = actorMembershipId;
        this.eventType = eventType;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.reportContentSha256 = request.getReportContentSha256();
        this.manifestSha256 = request.getManifestSha256();
        this.evidenceRevision = request.getEvidenceRevision();
        this.caseVersionBefore = caseVersionBefore;
        this.caseVersionAfter = caseVersionAfter;
        this.reason = reason;
        this.reviewReceiptId = reviewReceiptId;
        this.approvalDecisionId = approvalDecisionId;
        this.occurredAt = occurredAt;
    }
}
