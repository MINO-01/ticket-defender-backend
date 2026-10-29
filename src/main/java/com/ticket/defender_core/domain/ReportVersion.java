package com.ticket.defender_core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
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
@Table(name = "report_versions", uniqueConstraints =
        @UniqueConstraint(name = "uk_report_versions_case_revision", columnNames = {"case_id", "revision"}))
public class ReportVersion {
    public enum Status { DRAFT, PENDING_APPROVAL, APPROVED, REJECTED, WITHDRAWN, INVALIDATED }

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
    @Column(name = "evidence_snapshot_id", length = 36, nullable = false, updatable = false)
    private UUID evidenceSnapshotId;
    @Column(name = "evidence_revision", nullable = false, updatable = false)
    private long evidenceRevision;
    @Column(name = "revision", nullable = false, updatable = false)
    private long revision;
    @Version @Column(name = "version", nullable = false)
    private Long version;
    @Column(name = "template_version", length = 64, nullable = false, updatable = false)
    private String templateVersion;
    @Column(name = "template_sha256", length = 64, updatable = false)
    private String templateSha256;
    @Column(name = "content_sha256", length = 64, nullable = false, updatable = false)
    private String contentSha256;
    @Column(name = "content_json", columnDefinition = "text", nullable = false, updatable = false)
    private String contentJson;
    @Column(name = "status", length = 24, nullable = false)
    private String status;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "created_by_membership_id", length = 36, nullable = false, updatable = false)
    private UUID createdByMembershipId;
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public ReportVersion(Case target, EvidenceSnapshot snapshot, long revision,
            String templateVersion, String contentSha256, String contentJson,
            UUID actor, LocalDateTime now) {
        this(target, snapshot, revision, templateVersion, null, contentSha256, contentJson, actor, now);
    }

    public ReportVersion(Case target, EvidenceSnapshot snapshot, long revision,
            String templateVersion, String templateSha256, String contentSha256, String contentJson,
            UUID actor, LocalDateTime now) {
        this.id = UUID.randomUUID();
        this.tenantId = target.getTenantId();
        this.eventId = target.getEventId();
        this.caseId = target.getId();
        this.evidenceSnapshotId = snapshot.getId();
        this.evidenceRevision = snapshot.getEvidenceRevision();
        this.revision = revision;
        this.templateVersion = Objects.requireNonNull(templateVersion);
        this.templateSha256 = templateSha256;
        this.contentSha256 = Objects.requireNonNull(contentSha256);
        this.contentJson = Objects.requireNonNull(contentJson);
        this.status = Status.DRAFT.name();
        this.createdByMembershipId = Objects.requireNonNull(actor);
        this.createdAt = Objects.requireNonNull(now);
    }

    public void submitForApproval() {
        if (!Status.DRAFT.name().equals(status)) {
            throw new IllegalStateException("Only a draft report can be submitted");
        }
        status = Status.PENDING_APPROVAL.name();
    }

    public void approve() {
        close(Status.APPROVED);
    }

    public void reject() {
        close(Status.REJECTED);
    }

    public void withdraw() {
        close(Status.WITHDRAWN);
    }

    public void invalidate() {
        close(Status.INVALIDATED);
    }

    private void close(Status target) {
        if (!Status.PENDING_APPROVAL.name().equals(status)) {
            throw new IllegalStateException("Only a pending report can be closed");
        }
        status = target.name();
    }
}
