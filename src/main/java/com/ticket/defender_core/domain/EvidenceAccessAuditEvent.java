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

/**
 * 관리자 원본 이미지 열람 감사 기록입니다. 추가만 하며 수정하지 않습니다.
 * id는 요청마다 서버가 만든 값이라 한 요청에는 성공이나 실패 중 하나의 기록만 남습니다.
 * 객체 키, 객체 버전, 이미지 바이트, 인증 토큰은 저장하지 않습니다.
 */
@Entity
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "evidence_access_audit_events", indexes = {
        @Index(name = "idx_evidence_access_audit_scope_time",
                columnList = "tenant_id,event_id,fan_report_id,occurred_at"),
        @Index(name = "idx_evidence_access_audit_evidence_time", columnList = "evidence_id,occurred_at")})
public class EvidenceAccessAuditEvent {
    public enum Outcome { SUCCEEDED, FAILED }

    /** 성공 기록에만 쓰는 상세 코드입니다. */
    public static final String VERIFIED = "VERIFIED_FOR_RESPONSE";

    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "id", length = 36, nullable = false, updatable = false)
    private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "tenant_id", length = 36, nullable = false, updatable = false)
    private UUID tenantId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "event_id", length = 36, nullable = false, updatable = false)
    private UUID eventId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "fan_report_id", length = 36, nullable = false, updatable = false)
    private UUID fanReportId;
    /** 요청한 증거가 이 제보 소속으로 확인되지 않으면 다른 범위의 ID를 남기지 않도록 비워 둡니다. */
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "evidence_id", length = 36, updatable = false)
    private UUID evidenceId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "actor_membership_id", length = 36, nullable = false, updatable = false)
    private UUID actorMembershipId;
    @Enumerated(EnumType.STRING) @Column(name = "outcome", length = 16, nullable = false, updatable = false)
    private Outcome outcome;
    @Column(name = "detail_code", length = 64, nullable = false, updatable = false)
    private String detailCode;
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt;

    public EvidenceAccessAuditEvent(UUID requestId, UUID tenantId, UUID eventId, UUID fanReportId, UUID evidenceId,
            UUID actorMembershipId, Outcome outcome, String detailCode, LocalDateTime occurredAt) {
        this.id = Objects.requireNonNull(requestId);
        this.tenantId = Objects.requireNonNull(tenantId);
        this.eventId = Objects.requireNonNull(eventId);
        this.fanReportId = Objects.requireNonNull(fanReportId);
        this.evidenceId = evidenceId;
        this.actorMembershipId = Objects.requireNonNull(actorMembershipId);
        this.outcome = Objects.requireNonNull(outcome);
        this.detailCode = Objects.requireNonNull(detailCode);
        this.occurredAt = Objects.requireNonNull(occurredAt);
        if ((outcome == Outcome.SUCCEEDED) != VERIFIED.equals(detailCode)
                || (outcome == Outcome.SUCCEEDED && evidenceId == null))
            throw new IllegalArgumentException("Audit outcome and detail code do not match");
    }
}
