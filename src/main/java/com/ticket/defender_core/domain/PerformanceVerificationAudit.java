package com.ticket.defender_core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

/** 검증 회차의 변경 전후를 덮어쓰지 않는 감사 행입니다. */
@Entity
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "performance_verification_audits", uniqueConstraints =
        @UniqueConstraint(name = "uk_performance_verification_audit_revision",
                columnNames = {"performance_id", "revision"}))
public class PerformanceVerificationAudit {
    public enum Action { VERIFIED, REVISED, WITHDRAWN }

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", nullable = false, length = 36)
    private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "performance_id", nullable = false, length = 36)
    private UUID performanceId;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tenant_id", nullable = false, length = 36)
    private UUID tenantId;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "event_id", nullable = false, length = 36)
    private UUID eventId;
    @Column(name = "performance_ref", nullable = false, length = 128)
    private String performanceRef;
    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 16)
    private Action action;
    @Column(name = "revision", nullable = false)
    private int revision;
    @Column(name = "verification_basis", nullable = false, length = 512)
    private String verificationBasis;
    @Column(name = "source_version", nullable = false, length = 128)
    private String sourceVersion;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "actor_membership_id", nullable = false, length = 36)
    private UUID actorMembershipId;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_id", nullable = false, length = 36)
    private UUID requestId;
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    public PerformanceVerificationAudit(VerifiedPerformance performance, Action action, UUID actorMembershipId,
            UUID requestId, LocalDateTime occurredAt) {
        this.id = UUID.randomUUID();
        this.performanceId = performance.getId();
        this.tenantId = performance.getTenantId();
        this.eventId = performance.getEventId();
        this.performanceRef = performance.getPerformanceRef();
        this.action = Objects.requireNonNull(action);
        this.revision = performance.getRevision();
        this.verificationBasis = performance.getStatus() == VerifiedPerformance.Status.WITHDRAWN
                ? performance.getWithdrawalReason() : performance.getVerificationBasis();
        this.sourceVersion = performance.getSourceVersion();
        this.actorMembershipId = Objects.requireNonNull(actorMembershipId);
        this.requestId = Objects.requireNonNull(requestId);
        this.occurredAt = Objects.requireNonNull(occurredAt);
    }
}
