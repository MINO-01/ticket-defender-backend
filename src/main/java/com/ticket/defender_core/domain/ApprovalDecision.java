package com.ticket.defender_core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/** Immutable human review decision. It is not a PDF issuance authorization. */
@Entity
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "approval_decisions", uniqueConstraints = {
        @UniqueConstraint(name = "uk_approval_decision_request", columnNames = "approval_request_id"),
        @UniqueConstraint(name = "uk_approval_decision_receipt", columnNames = "review_receipt_id"),
        @UniqueConstraint(name = "uk_approval_decision_scope_id", columnNames = {
                "tenant_id", "event_id", "case_id", "id"})
}, indexes = @Index(name = "idx_approval_decision_scope_time", columnList =
        "tenant_id,event_id,case_id,decided_at"))
public class ApprovalDecision {
    public enum Outcome { APPROVE, REJECT }

    @Id @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", length = 36, nullable = false)
    private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tenant_id", length = 36, nullable = false)
    private UUID tenantId;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "event_id", length = 36, nullable = false)
    private UUID eventId;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "case_id", length = 36, nullable = false)
    private UUID caseId;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "approval_request_id", length = 36, nullable = false)
    private UUID approvalRequestId;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "review_receipt_id", length = 36, nullable = false)
    private UUID reviewReceiptId;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "report_version_id", length = 36, nullable = false)
    private UUID reportVersionId;
    @Column(name = "report_revision", nullable = false)
    private long reportRevision;
    @Column(name = "report_content_sha256", length = 64, nullable = false)
    private String reportContentSha256;
    @Column(name = "report_template_version", length = 64, nullable = false)
    private String reportTemplateVersion;
    @Column(name = "report_template_sha256", length = 64, nullable = false)
    private String reportTemplateSha256;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "evidence_snapshot_id", length = 36, nullable = false)
    private UUID evidenceSnapshotId;
    @Column(name = "evidence_revision", nullable = false)
    private long evidenceRevision;
    @Column(name = "manifest_sha256", length = 64, nullable = false)
    private String manifestSha256;
    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 16, nullable = false)
    private Outcome outcome;
    @Column(name = "reason", length = 2000, nullable = false)
    private String reason;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "decided_by_membership_id", length = 36, nullable = false)
    private UUID decidedByMembershipId;
    @Column(name = "case_version_before", nullable = false)
    private long caseVersionBefore;
    @Column(name = "case_version_after", nullable = false)
    private long caseVersionAfter;
    @Column(name = "request_version_at_decision", nullable = false)
    private long requestVersionAtDecision;
    @Column(name = "report_version_at_decision", nullable = false)
    private long reportVersionAtDecision;
    @Column(name = "decided_at", nullable = false)
    private LocalDateTime decidedAt;

    public ApprovalDecision(ApprovalRequest request, ReviewReceipt receipt, Case target,
            UUID decidedByMembershipId, Outcome outcome, String reason, long caseVersionBefore,
            long requestVersionAtDecision, long reportVersionAtDecision, LocalDateTime decidedAt) {
        this.id = UUID.randomUUID();
        this.tenantId = request.getTenantId();
        this.eventId = request.getEventId();
        this.caseId = request.getCaseId();
        this.approvalRequestId = request.getId();
        this.reviewReceiptId = receipt.getId();
        this.reportVersionId = request.getReportVersionId();
        this.reportRevision = request.getReportRevision();
        this.reportContentSha256 = request.getReportContentSha256();
        this.reportTemplateVersion = request.getReportTemplateVersion();
        this.reportTemplateSha256 = request.getReportTemplateSha256();
        this.evidenceSnapshotId = request.getEvidenceSnapshotId();
        this.evidenceRevision = request.getEvidenceRevision();
        this.manifestSha256 = request.getManifestSha256();
        this.outcome = Objects.requireNonNull(outcome);
        this.reason = Objects.requireNonNull(reason);
        this.decidedByMembershipId = Objects.requireNonNull(decidedByMembershipId);
        this.caseVersionBefore = caseVersionBefore;
        this.caseVersionAfter = target.getCaseVersion();
        this.requestVersionAtDecision = requestVersionAtDecision;
        this.reportVersionAtDecision = reportVersionAtDecision;
        this.decidedAt = Objects.requireNonNull(decidedAt);
    }
}
