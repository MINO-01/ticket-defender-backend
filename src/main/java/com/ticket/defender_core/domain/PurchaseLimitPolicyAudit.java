package com.ticket.defender_core.domain;

import jakarta.persistence.*;
import lombok.AccessLevel; import lombok.Getter; import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable; import org.hibernate.annotations.JdbcTypeCode; import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime; import java.util.UUID;

@Entity @Immutable @Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "purchase_limit_policy_audits", uniqueConstraints = @UniqueConstraint(name = "uk_purchase_limit_policy_audit_revision", columnNames = {"policy_id", "revision"}))
public class PurchaseLimitPolicyAudit {
    public enum Action { CREATED, REVISED, WITHDRAWN }
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length=36) private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name="policy_id", nullable=false, length=36) private UUID policyId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name="tenant_id", nullable=false, length=36) private UUID tenantId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name="event_id", nullable=false, length=36) private UUID eventId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name="performance_id", nullable=false, length=36) private UUID performanceId;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=16) private Action action;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=16) private PurchaseLimitPolicy.Status status;
    @Column(name="max_tickets_per_account", nullable=false) private int maxTicketsPerAccount;
    @Column(name="policy_version", nullable=false, length=128) private String policyVersion;
    @Column(name="effective_from", nullable=false) private LocalDateTime effectiveFrom;
    @Column(name="effective_until") private LocalDateTime effectiveUntil;
    @Column(name="policy_source", nullable=false, length=512) private String policySource;
    @Column(name="verification_basis", nullable=false, length=512) private String verificationBasis;
    @Column(nullable=false) private int revision;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name="actor_membership_id", nullable=false, length=36) private UUID actorMembershipId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name="request_id", nullable=false, length=36) private UUID requestId;
    @Column(name="occurred_at", nullable=false) private LocalDateTime occurredAt;
    public PurchaseLimitPolicyAudit(PurchaseLimitPolicy p, Action action, UUID actor, UUID requestId, LocalDateTime now) {
        id=UUID.randomUUID(); policyId=p.getId(); tenantId=p.getTenantId(); eventId=p.getEventId(); performanceId=p.getPerformanceId(); this.action=action; status=p.getStatus(); maxTicketsPerAccount=p.getMaxTicketsPerAccount(); policyVersion=p.getPolicyVersion(); effectiveFrom=p.getEffectiveFrom(); effectiveUntil=p.getEffectiveUntil(); policySource=p.getPolicySource(); verificationBasis=p.getVerificationBasis(); revision=p.getRevision(); actorMembershipId=actor; this.requestId=requestId; occurredAt=now;
    }
}
