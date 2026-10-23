package com.ticket.defender_core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Immutable;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/** Append-only business activity, distinct from future approval audit events. */
@Entity
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "case_activities", indexes =
        @Index(name = "idx_case_activities_scope_time", columnList = "tenant_id,event_id,case_id,occurred_at"))
public class CaseActivity {
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "id", length = 36, nullable = false)
    private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "case_id", length = 36, nullable = false)
    private UUID caseId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "tenant_id", length = 36, nullable = false)
    private UUID tenantId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "event_id", length = 36, nullable = false)
    private UUID eventId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "actor_membership_id", length = 36, nullable = false)
    private UUID actorMembershipId;
    @Column(name = "actor_role", length = 24, nullable = false)
    private String actorRole;
    @Column(name = "event_type", length = 48, nullable = false)
    private String eventType;
    @Column(name = "reason", length = 2000, nullable = false)
    private String reason;
    @Column(name = "from_status", length = 32)
    private String fromStatus;
    @Column(name = "to_status", length = 32)
    private String toStatus;
    @Column(name = "version_before")
    private Long versionBefore;
    @Column(name = "version_after", nullable = false)
    private long versionAfter;
    @Column(name = "evidence_revision", nullable = false)
    private long evidenceRevision;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "source_reference_id", length = 36)
    private UUID sourceReferenceId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "previous_assignee_membership_id", length = 36)
    private UUID previousAssigneeMembershipId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "new_assignee_membership_id", length = 36)
    private UUID newAssigneeMembershipId;
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    public CaseActivity(Case target, UUID actorMembershipId, String actorRole, String eventType, String reason,
            Case.Status from, Case.Status to, Long versionBefore, long versionAfter,
            UUID sourceReferenceId, LocalDateTime now) {
        this(target, actorMembershipId, actorRole, eventType, reason, from, to, versionBefore,
                versionAfter, sourceReferenceId, null, null, now);
    }

    public CaseActivity(Case target, UUID actorMembershipId, String actorRole, String eventType, String reason,
            Case.Status from, Case.Status to, Long versionBefore, long versionAfter,
            UUID sourceReferenceId, UUID previousAssigneeMembershipId,
            UUID newAssigneeMembershipId, LocalDateTime now) {
        this.id = UUID.randomUUID();
        this.caseId = target.getId();
        this.tenantId = target.getTenantId();
        this.eventId = target.getEventId();
        this.actorMembershipId = actorMembershipId;
        this.actorRole = actorRole;
        this.eventType = eventType;
        this.reason = reason;
        this.fromStatus = from == null ? null : from.name();
        this.toStatus = to == null ? null : to.name();
        this.versionBefore = versionBefore;
        this.versionAfter = versionAfter;
        this.evidenceRevision = target.getEvidenceRevision();
        this.sourceReferenceId = sourceReferenceId;
        this.previousAssigneeMembershipId = previousAssigneeMembershipId;
        this.newAssigneeMembershipId = newAssigneeMembershipId;
        this.occurredAt = now;
    }
}
