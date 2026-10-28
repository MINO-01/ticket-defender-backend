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

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "approval_requests", uniqueConstraints = {
        @UniqueConstraint(name = "uk_approval_request_report", columnNames = "report_version_id"),
        @UniqueConstraint(name = "uk_approval_request_active_case",
                columnNames = {"tenant_id", "active_case_id"}),
        @UniqueConstraint(name = "uk_approval_request_scope_id",
                columnNames = {"tenant_id", "event_id", "case_id", "id"})
}, indexes = @Index(name = "idx_approval_request_scope_time",
        columnList = "tenant_id,event_id,case_id,requested_at"))
public class ApprovalRequest {
    public enum Status { PENDING, APPROVED, REJECTED, WITHDRAWN, INVALIDATED }

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
    @Column(name = "requested_by_membership_id", length = 36, nullable = false, updatable = false)
    private UUID requestedByMembershipId;
    @Column(name = "requested_at", nullable = false, updatable = false)
    private LocalDateTime requestedAt;
    @Column(name = "reason", length = 2000, nullable = false, updatable = false)
    private String reason;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private Status status;
    @Column(name = "case_version_at_submit", nullable = false, updatable = false)
    private long caseVersionAtSubmit;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "active_case_id", length = 36)
    private UUID activeCaseId;
    @Version
    @Column(name = "request_version", nullable = false)
    private Long version;

    public ApprovalRequest(Case target, ReportVersion report, EvidenceSnapshot snapshot,
            UUID requestedByMembershipId, String reason, LocalDateTime requestedAt) {
        this.id = UUID.randomUUID();
        this.tenantId = target.getTenantId();
        this.eventId = target.getEventId();
        this.caseId = target.getId();
        this.reportVersionId = report.getId();
        this.reportRevision = report.getRevision();
        this.reportContentSha256 = report.getContentSha256();
        this.reportTemplateVersion = report.getTemplateVersion();
        this.reportTemplateSha256 = Objects.requireNonNull(report.getTemplateSha256());
        this.evidenceSnapshotId = snapshot.getId();
        this.evidenceRevision = snapshot.getEvidenceRevision();
        this.manifestSha256 = snapshot.getManifestSha256();
        this.requestedByMembershipId = Objects.requireNonNull(requestedByMembershipId);
        this.requestedAt = Objects.requireNonNull(requestedAt);
        this.reason = Objects.requireNonNull(reason);
        this.status = Status.PENDING;
        this.caseVersionAtSubmit = target.getCaseVersion();
        this.activeCaseId = target.getId();
    }
}
