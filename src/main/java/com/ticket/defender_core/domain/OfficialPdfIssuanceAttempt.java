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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/** Mutable execution attempt, retained separately from the immutable official artifact. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "official_pdf_issuance_attempts", uniqueConstraints = @UniqueConstraint(
        name = "uk_official_pdf_attempt_number", columnNames = {"issuance_id", "attempt_number"}))
public class OfficialPdfIssuanceAttempt {
    public enum Kind { REQUEST, RETRY, RECOVERY }
    public enum Status { RUNNING, SUCCEEDED, FAILED, STALE }

    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "id", length = 36, nullable = false, updatable = false)
    private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "issuance_id", length = 36, nullable = false, updatable = false)
    private UUID issuanceId;
    @Column(name = "attempt_number", nullable = false, updatable = false)
    private int attemptNumber;
    @Enumerated(EnumType.STRING) @Column(name = "attempt_kind", length = 16, nullable = false, updatable = false)
    private Kind kind;
    @Enumerated(EnumType.STRING) @Column(name = "status", length = 16, nullable = false)
    private Status status;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "actor_membership_id", length = 36, nullable = false, updatable = false)
    private UUID actorMembershipId;
    @Column(name = "failure_code", length = 96)
    private String failureCode;
    @Column(name = "started_at", nullable = false, updatable = false)
    private LocalDateTime startedAt;
    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    public OfficialPdfIssuanceAttempt(OfficialPdfIssuance issuance, int attemptNumber, Kind kind,
            UUID actorMembershipId, LocalDateTime now) {
        this.id = UUID.randomUUID();
        this.issuanceId = issuance.getId();
        this.attemptNumber = attemptNumber;
        this.kind = Objects.requireNonNull(kind);
        this.status = Status.RUNNING;
        this.actorMembershipId = Objects.requireNonNull(actorMembershipId);
        this.startedAt = Objects.requireNonNull(now);
    }

    public void succeeded(LocalDateTime now) { complete(Status.SUCCEEDED, null, now); }
    public void failed(String code, LocalDateTime now) { complete(Status.FAILED, code, now); }
    public void stale(String code, LocalDateTime now) { complete(Status.STALE, code, now); }

    private void complete(Status result, String code, LocalDateTime now) {
        if (status != Status.RUNNING) throw new IllegalStateException("Official PDF attempt is already terminal");
        this.status = result;
        this.failureCode = code;
        this.finishedAt = Objects.requireNonNull(now);
    }
}
