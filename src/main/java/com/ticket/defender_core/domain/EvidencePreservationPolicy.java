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
@Table(name = "evidence_preservation_policies", uniqueConstraints =
        @UniqueConstraint(name = "uk_preservation_policy_version", columnNames = {"policy_id", "policy_version"}),
        indexes = @Index(name = "idx_preservation_policy_scope", columnList =
                "tenant_id,event_id,policy_id,policy_version"))
public class EvidencePreservationPolicy {
    public enum Status { DRAFT, APPROVED, REVOKED, EXPIRED }
    public enum Mode { GOVERNANCE, COMPLIANCE }

    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "id", length = 36, nullable = false)
    private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "policy_id", length = 36, nullable = false)
    private UUID policyId;
    @Column(name = "policy_version", nullable = false)
    private long policyVersion;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "tenant_id", length = 36, nullable = false)
    private UUID tenantId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "event_id", length = 36)
    private UUID eventId;
    @Enumerated(EnumType.STRING) @Column(name = "policy_status", length = 16, nullable = false)
    private Status policyStatus;
    @Column(name = "effective_from", nullable = false)
    private LocalDateTime effectiveFrom;
    @Column(name = "effective_until", nullable = false)
    private LocalDateTime effectiveUntil;
    @Column(name = "retention_limit_days", nullable = false)
    private int retentionLimitDays;
    @Column(name = "storage_profile", length = 64, nullable = false)
    private String storageProfile;
    @Enumerated(EnumType.STRING) @Column(name = "preservation_mode", length = 16, nullable = false)
    private Mode preservationMode;
    @Column(name = "approval_authority", length = 160)
    private String approvalAuthority;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "approved_at")
    private LocalDateTime approvedAt;
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    public EvidencePreservationPolicy(UUID policyId, long policyVersion, UUID tenantId, UUID eventId,
            Status status, LocalDateTime effectiveFrom, LocalDateTime effectiveUntil,
            int retentionLimitDays, String storageProfile, Mode mode, String approvalAuthority,
            LocalDateTime createdAt, LocalDateTime approvedAt, LocalDateTime revokedAt) {
        if (policyVersion < 1) throw new IllegalArgumentException("Policy version must be positive");
        if (retentionLimitDays < 1 || retentionLimitDays > 3650)
            throw new IllegalArgumentException("Retention limit must be between 1 and 3650 days");
        if (effectiveFrom == null || effectiveUntil == null || !effectiveUntil.isAfter(effectiveFrom))
            throw new IllegalArgumentException("Policy effective period is invalid");
        if (storageProfile == null || storageProfile.isBlank() || storageProfile.length() > 64)
            throw new IllegalArgumentException("Storage profile is invalid");
        boolean approved = status != Status.DRAFT;
        if (approved && (approvedAt == null || approvalAuthority == null || approvalAuthority.isBlank()))
            throw new IllegalArgumentException("Approved policy metadata is required");
        if (!approved && (approvedAt != null || approvalAuthority != null || revokedAt != null))
            throw new IllegalArgumentException("Draft policy cannot contain approval metadata");
        if (status == Status.REVOKED && revokedAt == null)
            throw new IllegalArgumentException("Revoked policy requires a revocation time");
        if (status != Status.REVOKED && revokedAt != null)
            throw new IllegalArgumentException("Only a revoked policy can contain a revocation time");
        this.id = UUID.randomUUID();
        this.policyId = Objects.requireNonNull(policyId);
        this.policyVersion = policyVersion;
        this.tenantId = Objects.requireNonNull(tenantId);
        this.eventId = eventId;
        this.policyStatus = Objects.requireNonNull(status);
        this.effectiveFrom = effectiveFrom;
        this.effectiveUntil = effectiveUntil;
        this.retentionLimitDays = retentionLimitDays;
        this.storageProfile = storageProfile;
        this.preservationMode = Objects.requireNonNull(mode);
        this.approvalAuthority = approvalAuthority;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.approvedAt = approvedAt;
        this.revokedAt = revokedAt;
    }
}
