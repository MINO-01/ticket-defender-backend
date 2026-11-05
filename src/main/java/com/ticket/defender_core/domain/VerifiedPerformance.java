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

/** 고객사 공연 범위에서 관리자가 명시적으로 확인한 안정적 회차 식별자입니다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "verified_performances", uniqueConstraints = {
        @UniqueConstraint(name = "uk_verified_performances_scope_ref",
                columnNames = {"tenant_id", "event_id", "performance_ref"}),
        @UniqueConstraint(name = "uk_verified_performances_scope_id",
                columnNames = {"tenant_id", "event_id", "id"})
}, indexes = @Index(name = "idx_verified_performances_scope_status", columnList = "tenant_id,event_id,status"))
public class VerifiedPerformance {
    public enum Status { ACTIVE, WITHDRAWN }

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", nullable = false, length = 36)
    private UUID id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tenant_id", nullable = false, length = 36)
    private UUID tenantId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "event_id", nullable = false, length = 36)
    private UUID eventId;

    @Column(name = "performance_ref", nullable = false, length = 128, updatable = false)
    private String performanceRef;

    @Column(name = "verification_basis", nullable = false, length = 512)
    private String verificationBasis;

    @Column(name = "source_version", nullable = false, length = 128)
    private String sourceVersion;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "verified_by_membership_id", nullable = false, length = 36)
    private UUID verifiedByMembershipId;

    @Column(name = "verified_at", nullable = false)
    private LocalDateTime verifiedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    @Column(name = "revision", nullable = false)
    private int revision;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "withdrawn_by_membership_id", length = 36)
    private UUID withdrawnByMembershipId;

    @Column(name = "withdrawn_at")
    private LocalDateTime withdrawnAt;

    @Column(name = "withdrawal_reason", length = 512)
    private String withdrawalReason;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    public VerifiedPerformance(UUID id, UUID tenantId, UUID eventId, String performanceRef,
            String verificationBasis, String sourceVersion, UUID verifiedByMembershipId, LocalDateTime verifiedAt) {
        this.id = Objects.requireNonNull(id);
        this.tenantId = Objects.requireNonNull(tenantId);
        this.eventId = Objects.requireNonNull(eventId);
        this.performanceRef = required(performanceRef, "performanceRef", 128);
        this.verificationBasis = required(verificationBasis, "verificationBasis", 512);
        this.sourceVersion = required(sourceVersion, "sourceVersion", 128);
        this.verifiedByMembershipId = Objects.requireNonNull(verifiedByMembershipId);
        this.verifiedAt = Objects.requireNonNull(verifiedAt);
        this.status = Status.ACTIVE;
        this.revision = 1;
    }

    public void revise(String verificationBasis, String sourceVersion, UUID actorMembershipId, LocalDateTime now) {
        requireActive();
        this.verificationBasis = required(verificationBasis, "verificationBasis", 512);
        this.sourceVersion = required(sourceVersion, "sourceVersion", 128);
        this.verifiedByMembershipId = Objects.requireNonNull(actorMembershipId);
        this.verifiedAt = Objects.requireNonNull(now);
        this.revision++;
    }

    public void withdraw(String reason, UUID actorMembershipId, LocalDateTime now) {
        requireActive();
        this.status = Status.WITHDRAWN;
        this.withdrawalReason = required(reason, "reason", 512);
        this.withdrawnByMembershipId = Objects.requireNonNull(actorMembershipId);
        this.withdrawnAt = Objects.requireNonNull(now);
        this.revision++;
    }

    private void requireActive() {
        if (status != Status.ACTIVE) throw new IllegalStateException("Performance is not active");
    }

    private static String required(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max)
            throw new IllegalArgumentException(field + " is invalid");
        return value;
    }
}
