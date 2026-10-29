package com.ticket.defender_core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/** Server-issued, one-time proof that an approver opened a sealed review package. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "review_receipts", uniqueConstraints = {
        @UniqueConstraint(name = "uk_review_receipt_token", columnNames = "token_sha256"),
        @UniqueConstraint(name = "uk_review_receipt_active", columnNames = {
                "tenant_id", "approver_membership_id", "active_approval_request_id"}),
        @UniqueConstraint(name = "uk_review_receipt_scope_id", columnNames = {
                "tenant_id", "event_id", "case_id", "id"})
}, indexes = @Index(name = "idx_review_receipt_request_approver", columnList =
        "approval_request_id,approver_membership_id,issued_at"))
public class ReviewReceipt {
    public enum Status { ISSUED, CONSUMED, REVOKED, EXPIRED }

    @Id @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tenant_id", length = 36, nullable = false, updatable = false)
    private UUID tenantId;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "event_id", length = 36, nullable = false, updatable = false)
    private UUID eventId;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "case_id", length = 36, nullable = false, updatable = false)
    private UUID caseId;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "approval_request_id", length = 36, nullable = false, updatable = false)
    private UUID approvalRequestId;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "report_version_id", length = 36, nullable = false, updatable = false)
    private UUID reportVersionId;
    @Column(name = "report_revision", nullable = false, updatable = false)
    private long reportRevision;
    @Column(name = "report_content_sha256", length = 64, nullable = false, updatable = false)
    private String reportContentSha256;
    @Column(name = "report_template_version", length = 64, nullable = false, updatable = false)
    private String reportTemplateVersion;
    @Column(name = "report_template_sha256", length = 64, nullable = false, updatable = false)
    private String reportTemplateSha256;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "evidence_snapshot_id", length = 36, nullable = false, updatable = false)
    private UUID evidenceSnapshotId;
    @Column(name = "evidence_revision", nullable = false, updatable = false)
    private long evidenceRevision;
    @Column(name = "manifest_sha256", length = 64, nullable = false, updatable = false)
    private String manifestSha256;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "approver_membership_id", length = 36, nullable = false, updatable = false)
    private UUID approverMembershipId;
    @Column(name = "token_sha256", length = 64, nullable = false, updatable = false)
    private String tokenSha256;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private Status status;
    @Column(name = "issued_at", nullable = false, updatable = false)
    private LocalDateTime issuedAt;
    @Column(name = "expires_at", nullable = false, updatable = false)
    private LocalDateTime expiresAt;
    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "active_approval_request_id", length = 36)
    private UUID activeApprovalRequestId;
    @Version
    @Column(name = "receipt_version", nullable = false)
    private Long version;

    public ReviewReceipt(ApprovalRequest request, UUID approverMembershipId, String tokenSha256,
            LocalDateTime issuedAt, LocalDateTime expiresAt) {
        this.id = UUID.randomUUID();
        this.tenantId = request.getTenantId();
        this.eventId = request.getEventId();
        this.caseId = request.getCaseId();
        this.approvalRequestId = request.getId();
        this.reportVersionId = request.getReportVersionId();
        this.reportRevision = request.getReportRevision();
        this.reportContentSha256 = request.getReportContentSha256();
        this.reportTemplateVersion = request.getReportTemplateVersion();
        this.reportTemplateSha256 = request.getReportTemplateSha256();
        this.evidenceSnapshotId = request.getEvidenceSnapshotId();
        this.evidenceRevision = request.getEvidenceRevision();
        this.manifestSha256 = request.getManifestSha256();
        this.approverMembershipId = Objects.requireNonNull(approverMembershipId);
        this.tokenSha256 = Objects.requireNonNull(tokenSha256);
        this.status = Status.ISSUED;
        this.issuedAt = Objects.requireNonNull(issuedAt);
        this.expiresAt = Objects.requireNonNull(expiresAt);
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("Receipt expiry must be after issue time");
        }
        this.activeApprovalRequestId = request.getId();
    }

    public boolean isExpiredAt(LocalDateTime now) {
        return !expiresAt.isAfter(now);
    }

    public void consume(LocalDateTime now) {
        if (status != Status.ISSUED || isExpiredAt(now)) {
            throw new IllegalStateException("Review receipt is not consumable");
        }
        this.status = Status.CONSUMED;
        this.consumedAt = Objects.requireNonNull(now);
        this.activeApprovalRequestId = null;
    }

    public void revoke(LocalDateTime now) {
        if (status != Status.ISSUED) {
            throw new IllegalStateException("Only an issued review receipt can be revoked");
        }
        this.status = Status.REVOKED;
        this.revokedAt = Objects.requireNonNull(now);
        this.activeApprovalRequestId = null;
    }

    public void expire(LocalDateTime now) {
        if (status != Status.ISSUED || !isExpiredAt(now)) {
            throw new IllegalStateException("Only an expired issued receipt can expire");
        }
        this.status = Status.EXPIRED;
        this.activeApprovalRequestId = null;
    }
}
