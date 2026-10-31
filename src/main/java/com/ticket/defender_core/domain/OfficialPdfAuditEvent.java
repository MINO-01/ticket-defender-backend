package com.ticket.defender_core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/** Append-only official PDF audit record. It intentionally contains neither object keys nor PDF bytes. */
@Entity
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "official_pdf_audit_events", indexes = @Index(name = "idx_official_pdf_audit_scope_time",
        columnList = "tenant_id,event_id,case_id,occurred_at"))
public class OfficialPdfAuditEvent {
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "id", length = 36, nullable = false, updatable = false)
    private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "tenant_id", length = 36, nullable = false, updatable = false)
    private UUID tenantId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "event_id", length = 36, nullable = false, updatable = false)
    private UUID eventId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "case_id", length = 36, nullable = false, updatable = false)
    private UUID caseId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "issuance_id", length = 36, nullable = false, updatable = false)
    private UUID issuanceId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "artifact_id", length = 36)
    private UUID artifactId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "actor_membership_id", length = 36, nullable = false, updatable = false)
    private UUID actorMembershipId;
    @Column(name = "event_type", length = 48, nullable = false, updatable = false)
    private String eventType;
    @Column(name = "detail_code", length = 96, nullable = false, updatable = false)
    private String detailCode;
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt;

    public OfficialPdfAuditEvent(OfficialPdfIssuance issuance, UUID artifactId, UUID actorMembershipId,
            String eventType, String detailCode, LocalDateTime occurredAt) {
        this.id = UUID.randomUUID();
        this.tenantId = issuance.getTenantId();
        this.eventId = issuance.getEventId();
        this.caseId = issuance.getCaseId();
        this.issuanceId = issuance.getId();
        this.artifactId = artifactId;
        this.actorMembershipId = Objects.requireNonNull(actorMembershipId);
        this.eventType = Objects.requireNonNull(eventType);
        this.detailCode = Objects.requireNonNull(detailCode);
        this.occurredAt = Objects.requireNonNull(occurredAt);
    }
}
