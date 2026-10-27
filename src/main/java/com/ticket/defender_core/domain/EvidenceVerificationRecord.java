package com.ticket.defender_core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import java.util.Objects;
import java.util.UUID;

@Entity
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "evidence_verification_records", indexes = {
        @Index(name = "idx_evidence_verifications_case_time", columnList = "tenant_id,event_id,case_id,verified_at"),
        @Index(name = "idx_evidence_verifications_source_time", columnList = "evidence_snapshot_id,source_reference_id,verified_at")
})
public class EvidenceVerificationRecord {
    public enum Status { MATCH, HASH_MISMATCH, SIZE_MISMATCH, OBJECT_MISSING, OBJECT_CHANGED,
        STORAGE_UNAVAILABLE, TOO_LARGE, RETENTION_EXPIRED }
    public enum Preservation { UNPROTECTED, VERSIONED_MUTABLE, UNKNOWN }
    public enum MarkerKind { VERSION_ID, ETAG, NONE }
    public enum Method { STREAMED_SHA256, NOT_READ }

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
    @Column(name = "manifest_sha256", length = 64, nullable = false)
    private String manifestSha256;
    @Column(name = "evidence_revision", nullable = false)
    private long evidenceRevision;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "source_reference_id", length = 36, nullable = false)
    private UUID sourceReferenceId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "fan_report_id", length = 36, nullable = false)
    private UUID fanReportId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "evidence_id", length = 36, nullable = false)
    private UUID evidenceId;
    @Column(name = "object_key_sha256", length = 64, nullable = false)
    private String objectKeySha256;
    @Column(name = "observed_marker", length = 160)
    private String observedMarker;
    @Enumerated(EnumType.STRING) @Column(name = "marker_kind", length = 16, nullable = false)
    private MarkerKind markerKind;
    @Column(name = "expected_sha256", length = 64, nullable = false)
    private String expectedSha256;
    @Column(name = "verified_sha256", length = 64)
    private String verifiedSha256;
    @Column(name = "expected_byte_size", nullable = false)
    private long expectedByteSize;
    @Column(name = "verified_byte_size")
    private Long verifiedByteSize;
    @Column(name = "media_type", length = 32, nullable = false)
    private String mediaType;
    @Column(name = "retention_until", nullable = false)
    private LocalDateTime retentionUntil;
    @Enumerated(EnumType.STRING) @Column(name = "verification_status", length = 32, nullable = false)
    private Status verificationStatus;
    @Enumerated(EnumType.STRING) @Column(name = "preservation_status", length = 32, nullable = false)
    private Preservation preservationStatus;
    @Enumerated(EnumType.STRING) @Column(name = "verification_method", length = 32, nullable = false)
    private Method verificationMethod;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "created_by_membership_id", length = 36, nullable = false)
    private UUID createdByMembershipId;
    @Column(name = "verified_at", nullable = false)
    private LocalDateTime verifiedAt;

    public EvidenceVerificationRecord(Case target, EvidenceSnapshot snapshot, CaseSourceReference source,
            EvidenceAsset asset, String objectKeySha256, String observedMarker, MarkerKind markerKind,
            String verifiedSha256, Long verifiedByteSize, LocalDateTime retentionUntil,
            Status status, UUID actor, LocalDateTime now) {
        this.id = UUID.randomUUID();
        this.tenantId = target.getTenantId();
        this.eventId = target.getEventId();
        this.caseId = target.getId();
        this.evidenceSnapshotId = snapshot.getId();
        this.manifestSha256 = snapshot.getManifestSha256();
        this.evidenceRevision = snapshot.getEvidenceRevision();
        this.sourceReferenceId = source.getId();
        this.fanReportId = asset.getFanReportId();
        this.evidenceId = asset.getEvidenceId();
        this.objectKeySha256 = Objects.requireNonNull(objectKeySha256);
        this.observedMarker = observedMarker;
        this.markerKind = Objects.requireNonNull(markerKind);
        this.expectedSha256 = asset.getSha256();
        this.verifiedSha256 = verifiedSha256;
        this.expectedByteSize = asset.getByteSize();
        this.verifiedByteSize = verifiedByteSize;
        this.mediaType = asset.getMediaType();
        this.retentionUntil = Objects.requireNonNull(retentionUntil);
        this.verificationStatus = Objects.requireNonNull(status);
        this.preservationStatus = status == Status.MATCH
                ? (markerKind == MarkerKind.VERSION_ID ? Preservation.VERSIONED_MUTABLE : Preservation.UNPROTECTED)
                : Preservation.UNKNOWN;
        this.verificationMethod = verifiedSha256 == null ? Method.NOT_READ : Method.STREAMED_SHA256;
        this.createdByMembershipId = Objects.requireNonNull(actor);
        this.verifiedAt = Objects.requireNonNull(now);
    }
}
