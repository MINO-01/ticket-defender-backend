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

@Entity
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "evidence_preservation_records", uniqueConstraints = @UniqueConstraint(
        name = "uk_preservation_object_policy", columnNames = {"tenant_id", "evidence_id",
        "storage_profile", "actual_version_id", "preservation_policy_id", "preservation_policy_version"}),
        indexes = {
                @Index(name = "idx_preservation_case_time", columnList = "tenant_id,event_id,case_id,verified_at"),
                @Index(name = "idx_preservation_source_time", columnList =
                        "evidence_snapshot_id,source_reference_id,verified_at")
        })
public class EvidencePreservationRecord {
    public enum Status { IMMUTABLE_VERIFIED }

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
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "source_reference_id", length = 36, nullable = false)
    private UUID sourceReferenceId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "evidence_id", length = 36, nullable = false)
    private UUID evidenceId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "verification_record_id", length = 36, nullable = false)
    private UUID evidenceVerificationRecordId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "preservation_policy_id", length = 36, nullable = false)
    private UUID preservationPolicyId;
    @Column(name = "preservation_policy_version", nullable = false)
    private long preservationPolicyVersion;
    @Column(name = "storage_profile", length = 64, nullable = false)
    private String storageProfile;
    @Column(name = "object_key_sha256", length = 64, nullable = false)
    private String objectKeySha256;
    @Column(name = "actual_version_id", length = 160, nullable = false)
    private String actualVersionId;
    @Column(name = "object_sha256", length = 64, nullable = false)
    private String objectSha256;
    @Column(name = "object_size", nullable = false)
    private long objectSize;
    @Enumerated(EnumType.STRING) @Column(name = "object_lock_mode", length = 16, nullable = false)
    private EvidencePreservationPolicy.Mode objectLockMode;
    @Column(name = "retain_until", nullable = false)
    private LocalDateTime retainUntil;
    @Enumerated(EnumType.STRING) @Column(name = "preservation_status", length = 32, nullable = false)
    private Status preservationStatus;
    @Column(name = "verified_at", nullable = false)
    private LocalDateTime verifiedAt;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "actor_membership_id", length = 36, nullable = false)
    private UUID actorMembershipId;

    public EvidencePreservationRecord(Case target, EvidenceSnapshot snapshot, CaseSourceReference source,
            EvidenceAsset asset, EvidenceVerificationRecord verification,
            EvidencePreservationPolicy policy, String storageProfile, String objectKeySha256,
            String actualVersionId, String objectSha256, long objectSize,
            EvidencePreservationPolicy.Mode objectLockMode, LocalDateTime retainUntil,
            UUID actorMembershipId, LocalDateTime verifiedAt) {
        this.id = UUID.randomUUID();
        this.tenantId = target.getTenantId();
        this.eventId = target.getEventId();
        this.caseId = target.getId();
        this.evidenceSnapshotId = snapshot.getId();
        this.sourceReferenceId = source.getId();
        this.evidenceId = asset.getEvidenceId();
        this.evidenceVerificationRecordId = verification.getId();
        this.preservationPolicyId = policy.getPolicyId();
        this.preservationPolicyVersion = policy.getPolicyVersion();
        this.storageProfile = Objects.requireNonNull(storageProfile);
        this.objectKeySha256 = Objects.requireNonNull(objectKeySha256);
        this.actualVersionId = Objects.requireNonNull(actualVersionId);
        this.objectSha256 = Objects.requireNonNull(objectSha256);
        this.objectSize = objectSize;
        this.objectLockMode = Objects.requireNonNull(objectLockMode);
        this.retainUntil = Objects.requireNonNull(retainUntil);
        this.preservationStatus = Status.IMMUTABLE_VERIFIED;
        this.verifiedAt = Objects.requireNonNull(verifiedAt);
        this.actorMembershipId = Objects.requireNonNull(actorMembershipId);
    }
}
