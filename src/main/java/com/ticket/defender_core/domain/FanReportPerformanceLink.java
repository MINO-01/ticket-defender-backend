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

/** 팬 제보에 대한 명시적 회차 연결의 이력입니다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "fan_report_performance_links", uniqueConstraints = {
        @UniqueConstraint(name = "uk_fan_report_performance_link_active", columnNames = "active_fan_report_id"),
        @UniqueConstraint(name = "uk_fan_report_performance_link_scope_id",
                columnNames = {"tenant_id", "event_id", "id"})
}, indexes = @Index(name = "idx_fan_report_performance_links_report", columnList = "fan_report_id,created_at"))
public class FanReportPerformanceLink {
    public enum Status { ACTIVE, REPLACED, WITHDRAWN }

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
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "fan_report_id", nullable = false, length = 36)
    private UUID fanReportId;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "performance_id", nullable = false, length = 36)
    private UUID performanceId;
    @Column(name = "performance_ref", nullable = false, length = 128)
    private String performanceRef;
    @Column(name = "performance_revision", nullable = false)
    private int performanceRevision;
    @Column(name = "link_revision", nullable = false)
    private int linkRevision;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "active_fan_report_id", length = 36)
    private UUID activeFanReportId;
    @Column(name = "reason", nullable = false, length = 512)
    private String reason;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "linked_by_membership_id", nullable = false, length = 36)
    private UUID linkedByMembershipId;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "ended_by_membership_id", length = 36)
    private UUID endedByMembershipId;
    @Column(name = "ended_at")
    private LocalDateTime endedAt;
    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    public FanReportPerformanceLink(UUID tenantId, UUID eventId, UUID fanReportId, VerifiedPerformance performance,
            int linkRevision, String reason, UUID actorMembershipId, LocalDateTime now) {
        this.id = UUID.randomUUID();
        this.tenantId = Objects.requireNonNull(tenantId);
        this.eventId = Objects.requireNonNull(eventId);
        this.fanReportId = Objects.requireNonNull(fanReportId);
        this.performanceId = Objects.requireNonNull(performance.getId());
        this.performanceRef = performance.getPerformanceRef();
        this.performanceRevision = performance.getRevision();
        this.linkRevision = linkRevision;
        this.status = Status.ACTIVE;
        this.activeFanReportId = fanReportId;
        this.reason = required(reason);
        this.linkedByMembershipId = Objects.requireNonNull(actorMembershipId);
        this.createdAt = Objects.requireNonNull(now);
    }

    public void replace(UUID actorMembershipId, LocalDateTime now) {
        close(Status.REPLACED, actorMembershipId, now);
    }

    public void withdraw(UUID actorMembershipId, LocalDateTime now) {
        close(Status.WITHDRAWN, actorMembershipId, now);
    }

    private void close(Status next, UUID actorMembershipId, LocalDateTime now) {
        if (status != Status.ACTIVE) throw new IllegalStateException("Performance link is not active");
        this.status = next;
        this.activeFanReportId = null;
        this.endedByMembershipId = Objects.requireNonNull(actorMembershipId);
        this.endedAt = Objects.requireNonNull(now);
    }

    private static String required(String value) {
        if (value == null || value.isBlank() || value.length() > 512) throw new IllegalArgumentException("Invalid reason");
        return value;
    }
}
