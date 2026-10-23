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
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "report_versions", uniqueConstraints =
        @UniqueConstraint(name = "uk_report_versions_case_revision", columnNames = {"case_id", "revision"}))
public class ReportVersion {
    public enum Status { DRAFT }

    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "id", length = 36, nullable = false)
    private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "tenant_id", length = 36, nullable = false)
    private UUID tenantId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "event_id", length = 36, nullable = false)
    private UUID eventId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "case_id", length = 36, nullable = false)
    private UUID caseId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "evidence_snapshot_id", length = 36, nullable = false)
    private UUID evidenceSnapshotId;
    @Column(name = "evidence_revision", nullable = false)
    private long evidenceRevision;
    @Column(name = "revision", nullable = false)
    private long revision;
    @Version @Column(name = "version", nullable = false)
    private Long version;
    @Column(name = "template_version", length = 64, nullable = false)
    private String templateVersion;
    @Column(name = "content_sha256", length = 64, nullable = false)
    private String contentSha256;
    @Column(name = "content_json", columnDefinition = "text", nullable = false)
    private String contentJson;
    @Column(name = "status", length = 16, nullable = false)
    private String status;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "created_by_membership_id", length = 36, nullable = false)
    private UUID createdByMembershipId;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public ReportVersion(Case target, EvidenceSnapshot snapshot, long revision,
            String templateVersion, String contentSha256, String contentJson,
            UUID actor, LocalDateTime now) {
        this.id = UUID.randomUUID();
        this.tenantId = target.getTenantId();
        this.eventId = target.getEventId();
        this.caseId = target.getId();
        this.evidenceSnapshotId = snapshot.getId();
        this.evidenceRevision = snapshot.getEvidenceRevision();
        this.revision = revision;
        this.templateVersion = Objects.requireNonNull(templateVersion);
        this.contentSha256 = Objects.requireNonNull(contentSha256);
        this.contentJson = Objects.requireNonNull(contentJson);
        this.status = Status.DRAFT.name();
        this.createdByMembershipId = Objects.requireNonNull(actor);
        this.createdAt = Objects.requireNonNull(now);
    }
}
