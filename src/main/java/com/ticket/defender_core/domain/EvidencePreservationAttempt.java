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
import java.util.UUID;

@Entity
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "evidence_preservation_attempts", indexes = @Index(
        name = "idx_preservation_attempt_case_time", columnList = "tenant_id,event_id,case_id,attempted_at"))
public class EvidencePreservationAttempt {
    public enum Outcome { VERIFIED, POLICY_REJECTED, STORAGE_REJECTED, STORAGE_UNAVAILABLE, CONFLICT }

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
    private UUID verificationRecordId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "requested_policy_id", length = 36, nullable = false)
    private UUID requestedPolicyId;
    @Column(name = "resolved_policy_version")
    private Long resolvedPolicyVersion;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "preservation_record_id", length = 36)
    private UUID preservationRecordId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "actor_membership_id", length = 36, nullable = false)
    private UUID actorMembershipId;
    @Enumerated(EnumType.STRING) @Column(name = "outcome", length = 32, nullable = false)
    private Outcome outcome;
    @Column(name = "reason_code", length = 64, nullable = false)
    private String reasonCode;
    @Column(name = "key_digest", length = 64, nullable = false)
    private String keyDigest;
    @Column(name = "request_digest", length = 64, nullable = false)
    private String requestDigest;
    @Column(name = "attempted_at", nullable = false)
    private LocalDateTime attemptedAt;

    public EvidencePreservationAttempt(Case target, EvidenceSnapshot snapshot, CaseSourceReference source,
            EvidenceAsset asset, EvidenceVerificationRecord verification, UUID requestedPolicyId,
            Long resolvedPolicyVersion, UUID preservationRecordId, UUID actorMembershipId,
            Outcome outcome, String reasonCode, String keyDigest, String requestDigest,
            LocalDateTime attemptedAt) {
        this.id = UUID.randomUUID();
        this.tenantId = target.getTenantId();
        this.eventId = target.getEventId();
        this.caseId = target.getId();
        this.evidenceSnapshotId = snapshot.getId();
        this.sourceReferenceId = source.getId();
        this.evidenceId = asset.getEvidenceId();
        this.verificationRecordId = verification.getId();
        this.requestedPolicyId = requestedPolicyId;
        this.resolvedPolicyVersion = resolvedPolicyVersion;
        this.preservationRecordId = preservationRecordId;
        this.actorMembershipId = actorMembershipId;
        this.outcome = outcome;
        this.reasonCode = reasonCode;
        this.keyDigest = keyDigest;
        this.requestDigest = requestDigest;
        this.attemptedAt = attemptedAt;
    }
}
