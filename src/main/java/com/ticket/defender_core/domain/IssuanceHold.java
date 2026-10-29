package com.ticket.defender_core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "issuance_holds", indexes = @Index(name = "idx_issuance_hold_scope_status",
        columnList = "tenant_id,event_id,case_id,status"))
public class IssuanceHold {
    public enum Status { ACTIVE, RELEASED }

    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "id", length = 36, nullable = false)
    private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "tenant_id", length = 36, nullable = false)
    private UUID tenantId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "event_id", length = 36, nullable = false)
    private UUID eventId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "case_id", length = 36, nullable = false)
    private UUID caseId;
    @Enumerated(EnumType.STRING) @Column(name = "status", length = 16, nullable = false)
    private Status status;
    @Column(name = "reason_code", length = 64, nullable = false)
    private String reasonCode;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "created_by_membership_id", length = 36, nullable = false)
    private UUID createdByMembershipId;
    @Column(name = "evidence_revision", nullable = false)
    private long evidenceRevision;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "released_at")
    private LocalDateTime releasedAt;
    @Version @Column(name = "hold_version", nullable = false)
    private Long version;

    public IssuanceHold(Case target, String reasonCode, UUID actor, LocalDateTime createdAt) {
        this.id = UUID.randomUUID();
        this.tenantId = target.getTenantId();
        this.eventId = target.getEventId();
        this.caseId = target.getId();
        this.status = Status.ACTIVE;
        this.reasonCode = Objects.requireNonNull(reasonCode);
        this.createdByMembershipId = Objects.requireNonNull(actor);
        this.evidenceRevision = target.getEvidenceRevision();
        this.createdAt = Objects.requireNonNull(createdAt);
    }
}
