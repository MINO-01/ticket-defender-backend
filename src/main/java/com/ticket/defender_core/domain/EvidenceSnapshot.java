package com.ticket.defender_core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
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

@Entity
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "evidence_snapshots", uniqueConstraints = {
        @UniqueConstraint(name = "uk_evidence_snapshots_case_revision", columnNames = {"case_id", "evidence_revision"}),
        @UniqueConstraint(name = "uk_evidence_snapshots_scope_id", columnNames = {"tenant_id", "event_id", "case_id", "id"})
})
public class EvidenceSnapshot {
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "id", length = 36, nullable = false)
    private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "tenant_id", length = 36, nullable = false)
    private UUID tenantId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "event_id", length = 36, nullable = false)
    private UUID eventId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "case_id", length = 36, nullable = false)
    private UUID caseId;
    @Column(name = "evidence_revision", nullable = false)
    private long evidenceRevision;
    @Column(name = "case_version_at_capture", nullable = false)
    private long caseVersionAtCapture;
    @Column(name = "manifest_schema_version", nullable = false)
    private int manifestSchemaVersion;
    @Column(name = "manifest_sha256", length = 64, nullable = false)
    private String manifestSha256;
    @Column(name = "manifest_json", columnDefinition = "text", nullable = false)
    private String manifestJson;
    @Column(name = "source_integrity", length = 32, nullable = false)
    private String sourceIntegrity;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "created_by_membership_id", length = 36, nullable = false)
    private UUID createdByMembershipId;
    @Column(name = "captured_at", nullable = false)
    private LocalDateTime capturedAt;

    public EvidenceSnapshot(Case target, long versionAtCapture, String manifestSha256,
            String manifestJson, String sourceIntegrity, UUID actor, LocalDateTime now) {
        this.id = UUID.randomUUID();
        this.tenantId = target.getTenantId();
        this.eventId = target.getEventId();
        this.caseId = target.getId();
        this.evidenceRevision = target.getEvidenceRevision();
        this.caseVersionAtCapture = versionAtCapture;
        this.manifestSchemaVersion = 1;
        this.manifestSha256 = Objects.requireNonNull(manifestSha256);
        this.manifestJson = Objects.requireNonNull(manifestJson);
        this.sourceIntegrity = Objects.requireNonNull(sourceIntegrity);
        this.createdByMembershipId = Objects.requireNonNull(actor);
        this.capturedAt = Objects.requireNonNull(now);
    }
}
