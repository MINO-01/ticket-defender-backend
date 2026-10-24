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

/** Human investigation, independent of TicketAudit and analysis status. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "cases", indexes = {
        @Index(name = "idx_cases_scope_status_created", columnList = "tenant_id,event_id,status,created_at"),
        @Index(name = "idx_cases_scope_creator", columnList = "tenant_id,event_id,created_by_membership_id"),
        @Index(name = "idx_cases_scope_assignee", columnList = "tenant_id,event_id,assignee_membership_id")
}, uniqueConstraints = @UniqueConstraint(name = "uk_cases_scope_id", columnNames = {"tenant_id", "event_id", "id"}))
public class Case {
    public enum Type { GRAPH_CLUSTER, FAN_REPORT, COMBINED }
    public enum Status { OPEN, TRIAGED, IN_REVIEW, NEEDS_EVIDENCE, READY_FOR_APPROVAL,
        APPROVED_FOR_REPORT, DISMISSED, CLOSED }

    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "id", length = 36, nullable = false)
    private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "tenant_id", length = 36, nullable = false)
    private UUID tenantId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "event_id", length = 36, nullable = false)
    private UUID eventId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "created_by_membership_id", length = 36, nullable = false)
    private UUID createdByMembershipId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "assignee_membership_id", length = 36)
    private UUID assigneeMembershipId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "related_case_id", length = 36)
    private UUID relatedCaseId;
    @Column(name = "relation_reason", length = 2000)
    private String relationReason;
    @Enumerated(EnumType.STRING) @Column(name = "case_type", length = 32, nullable = false)
    private Type type;
    @Enumerated(EnumType.STRING) @Column(name = "status", length = 32, nullable = false)
    private Status status;
    @Column(name = "classification", length = 160)
    private String classification;
    @Version @Column(name = "case_version", nullable = false)
    private Long caseVersion;
    @Column(name = "evidence_revision", nullable = false)
    private long evidenceRevision;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Case(UUID id, UUID tenantId, UUID eventId, UUID createdByMembershipId,
            Type type, UUID relatedCaseId, String relationReason, LocalDateTime now) {
        this.id = Objects.requireNonNull(id);
        this.tenantId = Objects.requireNonNull(tenantId);
        this.eventId = Objects.requireNonNull(eventId);
        this.createdByMembershipId = Objects.requireNonNull(createdByMembershipId);
        this.type = Objects.requireNonNull(type);
        this.relatedCaseId = relatedCaseId;
        this.relationReason = relationReason;
        this.status = Status.OPEN;
        this.evidenceRevision = 1;
        this.createdAt = Objects.requireNonNull(now);
        this.updatedAt = now;
    }

    public void transition(Status target, String classification, LocalDateTime now) {
        this.status = Objects.requireNonNull(target);
        if (classification != null) this.classification = classification;
        this.updatedAt = Objects.requireNonNull(now);
    }

    public void assign(UUID membershipId, LocalDateTime now) {
        this.assigneeMembershipId = membershipId;
        this.updatedAt = Objects.requireNonNull(now);
    }

    public void recordPreparation(LocalDateTime now) {
        this.updatedAt = Objects.requireNonNull(now);
    }
}
