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

/** 연결/교체/철회 명령과 멱등성 키를 보존하는 append-only 감사 행입니다. */
@Entity
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "performance_link_audits", uniqueConstraints =
        @UniqueConstraint(name = "uk_performance_link_audit_idempotency",
                columnNames = {"actor_membership_id", "fan_report_id", "idempotency_key_digest"}))
public class PerformanceLinkAudit {
    public enum Action { LINKED, REPLACED, WITHDRAWN }

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", nullable = false, length = 36)
    private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "link_id", nullable = false, length = 36)
    private UUID linkId;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tenant_id", nullable = false, length = 36)
    private UUID tenantId;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "event_id", nullable = false, length = 36)
    private UUID eventId;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "fan_report_id", nullable = false, length = 36)
    private UUID fanReportId;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "performance_id", nullable = false, length = 36)
    private UUID performanceId;
    @Column(name = "link_revision", nullable = false)
    private int linkRevision;
    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 16)
    private Action action;
    @Column(name = "reason", nullable = false, length = 512)
    private String reason;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "actor_membership_id", nullable = false, length = 36)
    private UUID actorMembershipId;
    @Column(name = "idempotency_key_digest", nullable = false, length = 64)
    private String idempotencyKeyDigest;
    @Column(name = "request_digest", nullable = false, length = 64)
    private String requestDigest;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_id", nullable = false, length = 36)
    private UUID requestId;
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    public PerformanceLinkAudit(FanReportPerformanceLink link, Action action, String reason,
            UUID actorMembershipId, String idempotencyKeyDigest, String requestDigest, UUID requestId,
            LocalDateTime occurredAt) {
        this.id = UUID.randomUUID();
        this.linkId = link.getId();
        this.tenantId = link.getTenantId();
        this.eventId = link.getEventId();
        this.fanReportId = link.getFanReportId();
        this.performanceId = link.getPerformanceId();
        this.linkRevision = link.getLinkRevision();
        this.action = Objects.requireNonNull(action);
        this.reason = Objects.requireNonNull(reason);
        this.actorMembershipId = Objects.requireNonNull(actorMembershipId);
        this.idempotencyKeyDigest = Objects.requireNonNull(idempotencyKeyDigest);
        this.requestDigest = Objects.requireNonNull(requestDigest);
        this.requestId = Objects.requireNonNull(requestId);
        this.occurredAt = Objects.requireNonNull(occurredAt);
    }
}
