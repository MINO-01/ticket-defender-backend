package com.ticket.defender_core.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/** Current, server-verified per-performance purchase limit. Its revisions are append-only in PurchaseLimitPolicyAudit. */
@Entity @Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "purchase_limit_policies", uniqueConstraints = {
        @UniqueConstraint(name = "uk_purchase_limit_policy_scope", columnNames = {"tenant_id", "event_id", "performance_id"})
}, indexes = @Index(name = "idx_purchase_limit_policy_scope_status", columnList = "tenant_id,event_id,status"))
public class PurchaseLimitPolicy {
    public enum Status { ACTIVE, WITHDRAWN }
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36) private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "tenant_id", nullable = false, length = 36) private UUID tenantId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "event_id", nullable = false, length = 36) private UUID eventId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "performance_id", nullable = false, length = 36) private UUID performanceId;
    @Column(name = "max_tickets_per_account", nullable = false) private int maxTicketsPerAccount;
    @Column(name = "policy_version", nullable = false, length = 128) private String policyVersion;
    @Column(name = "effective_from", nullable = false) private LocalDateTime effectiveFrom;
    @Column(name = "effective_until") private LocalDateTime effectiveUntil;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private Status status;
    @Column(name = "policy_source", nullable = false, length = 512) private String policySource;
    @Column(name = "verification_basis", nullable = false, length = 512) private String verificationBasis;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "changed_by_membership_id", nullable = false, length = 36) private UUID changedByMembershipId;
    @Column(name = "changed_at", nullable = false) private LocalDateTime changedAt;
    @Column(nullable = false) private int revision;
    @Version @Column(name = "row_version", nullable = false) private long rowVersion;

    public PurchaseLimitPolicy(UUID id, UUID tenantId, UUID eventId, UUID performanceId, int maximum, String version,
            LocalDateTime from, LocalDateTime until, String source, String basis, UUID actor, LocalDateTime now) {
        this.id=Objects.requireNonNull(id); this.tenantId=Objects.requireNonNull(tenantId); this.eventId=Objects.requireNonNull(eventId);
        this.performanceId=Objects.requireNonNull(performanceId); apply(maximum, version, from, until, source, basis);
        this.changedByMembershipId=Objects.requireNonNull(actor); this.changedAt=Objects.requireNonNull(now); this.status=Status.ACTIVE; this.revision=1;
    }
    public void revise(int maximum, String version, LocalDateTime from, LocalDateTime until, String source, String basis, UUID actor, LocalDateTime now) {
        if (status != Status.ACTIVE) throw new IllegalStateException("Policy is not active");
        if (policyVersion.equals(version)) throw new IllegalArgumentException("policyVersion must change");
        apply(maximum, version, from, until, source, basis); changedByMembershipId=Objects.requireNonNull(actor); changedAt=Objects.requireNonNull(now); revision++;
    }
    public void withdraw(UUID actor, LocalDateTime now) {
        if (status != Status.ACTIVE) throw new IllegalStateException("Policy is not active");
        status=Status.WITHDRAWN; changedByMembershipId=Objects.requireNonNull(actor); changedAt=Objects.requireNonNull(now); revision++;
    }
    private void apply(int maximum, String version, LocalDateTime from, LocalDateTime until, String source, String basis) {
        if (maximum < 1 || (until != null && !until.isAfter(from))) throw new IllegalArgumentException("Invalid policy range");
        maxTicketsPerAccount=maximum; policyVersion=required(version,128); effectiveFrom=Objects.requireNonNull(from); effectiveUntil=until;
        policySource=required(source,512); verificationBasis=required(basis,512);
    }
    private static String required(String value, int max) { if (value==null || value.isBlank() || value.length()>max) throw new IllegalArgumentException("Invalid policy value"); return value; }
}
