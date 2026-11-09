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
        @UniqueConstraint(name = "uk_purchase_limit_policy_active_scope_key", columnNames = {"tenant_id", "event_id", "active_scope_key"}),
        @UniqueConstraint(name = "uk_purchase_limit_policy_version_scope", columnNames = {"tenant_id", "event_id", "performance_id", "policy_version"})
}, indexes = @Index(name = "idx_purchase_limit_policy_scope_status", columnList = "tenant_id,event_id,status"))
public class PurchaseLimitPolicy {
    public enum Status { ACTIVE, WITHDRAWN }
    /** The provider-confirmed subject whose purchases the policy limits. */
    public enum LimitSubject { ACCOUNT, PERSON }
    /** The commercial scope to which the customer says this policy applies. */
    public enum ApplicationScope { EVENT, PERFORMANCE, SALES_PHASE }
    /** Whether cancelled/refunded quantities remain part of the policy calculation. */
    public enum CancellationRefundRule { NETTED, GROSS }
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36) private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "tenant_id", nullable = false, length = 36) private UUID tenantId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "event_id", nullable = false, length = 36) private UUID eventId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "performance_id", nullable = false, length = 36) private UUID performanceId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "active_performance_id", length = 36) private UUID activePerformanceId;
    @Column(name = "policy_scope_key", nullable = false, length = 256) private String policyScopeKey;
    @Column(name = "active_scope_key", length = 256) private String activeScopeKey;
    @Column(name = "max_tickets_per_account", nullable = false) private int maxTicketsPerAccount;
    @Column(name = "policy_version", nullable = false, length = 128) private String policyVersion;
    @Column(name = "effective_from", nullable = false) private LocalDateTime effectiveFrom;
    @Column(name = "effective_until") private LocalDateTime effectiveUntil;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private Status status;
    @Column(name = "policy_source", nullable = false, length = 512) private String policySource;
    @Column(name = "verification_basis", nullable = false, length = 512) private String verificationBasis;
    @Enumerated(EnumType.STRING) @Column(name = "limit_subject", length = 16) private LimitSubject limitSubject;
    @Enumerated(EnumType.STRING) @Column(name = "application_scope", length = 16) private ApplicationScope applicationScope;
    @Column(name = "sales_phase_ref", length = 128) private String salesPhaseRef;
    @Enumerated(EnumType.STRING) @Column(name = "cancellation_refund_rule", length = 16) private CancellationRefundRule cancellationRefundRule;
    @Column(name = "includes_presale") private Boolean includesPresale;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "changed_by_membership_id", nullable = false, length = 36) private UUID changedByMembershipId;
    @Column(name = "changed_at", nullable = false) private LocalDateTime changedAt;
    @Column(nullable = false) private int revision;
    @Version @Column(name = "row_version", nullable = false) private long rowVersion;

    public PurchaseLimitPolicy(UUID id, UUID tenantId, UUID eventId, UUID performanceId, int maximum, String version,
            LocalDateTime from, LocalDateTime until, String source, String basis, UUID actor, LocalDateTime now) {
        this(id, tenantId, eventId, performanceId, maximum, version, from, until, source, basis, null, null, null, null, null, actor, now);
    }
    public PurchaseLimitPolicy(UUID id, UUID tenantId, UUID eventId, UUID performanceId, int maximum, String version,
            LocalDateTime from, LocalDateTime until, String source, String basis, LimitSubject subject, ApplicationScope scope,
            String phaseRef, CancellationRefundRule refundRule, Boolean presale, UUID actor, LocalDateTime now) {
        this.id=Objects.requireNonNull(id); this.tenantId=Objects.requireNonNull(tenantId); this.eventId=Objects.requireNonNull(eventId);
        this.performanceId=Objects.requireNonNull(performanceId); this.activePerformanceId=performanceId; apply(maximum, version, from, until, source, basis, subject, scope, phaseRef, refundRule, presale); activateScope();
        this.changedByMembershipId=Objects.requireNonNull(actor); this.changedAt=Objects.requireNonNull(now); this.status=Status.ACTIVE; this.revision=1;
    }
    public void revise(int maximum, String version, LocalDateTime from, LocalDateTime until, String source, String basis, UUID actor, LocalDateTime now) {
        revise(maximum, version, from, until, source, basis, null, null, null, null, null, actor, now);
    }
    public void revise(int maximum, String version, LocalDateTime from, LocalDateTime until, String source, String basis,
            LimitSubject subject, ApplicationScope scope, String phaseRef, CancellationRefundRule refundRule, Boolean presale,
            UUID actor, LocalDateTime now) {
        if (status != Status.ACTIVE) throw new IllegalStateException("Policy is not active");
        if (policyVersion.equals(version)) throw new IllegalArgumentException("policyVersion must change");
        apply(maximum, version, from, until, source, basis, subject, scope, phaseRef, refundRule, presale); activateScope(); changedByMembershipId=Objects.requireNonNull(actor); changedAt=Objects.requireNonNull(now); revision++;
    }
    public void withdraw(UUID actor, LocalDateTime now) {
        if (status != Status.ACTIVE) throw new IllegalStateException("Policy is not active");
        status=Status.WITHDRAWN; activePerformanceId=null; activeScopeKey=null; changedByMembershipId=Objects.requireNonNull(actor); changedAt=Objects.requireNonNull(now); revision++;
    }
    /** Active legacy policies remain valid; only an analysis that needs these semantics treats their absence as unconfirmed. */
    public boolean hasConfirmedAccountLimitSemantics() {
        return limitSubject==LimitSubject.ACCOUNT && hasConfirmedLimitSemantics();
    }
    public boolean hasConfirmedLimitSemantics() {
        return limitSubject!=null && applicationScope!=null && cancellationRefundRule!=null && includesPresale!=null
                && (applicationScope!=ApplicationScope.SALES_PHASE || salesPhaseRef!=null);
    }
    private void apply(int maximum, String version, LocalDateTime from, LocalDateTime until, String source, String basis,
            LimitSubject subject, ApplicationScope scope, String phaseRef, CancellationRefundRule refundRule, Boolean presale) {
        if (maximum < 1 || (until != null && !until.isAfter(from))) throw new IllegalArgumentException("Invalid policy range");
        maxTicketsPerAccount=maximum; policyVersion=required(version,128); effectiveFrom=Objects.requireNonNull(from); effectiveUntil=until;
        policySource=required(source,512); verificationBasis=required(basis,512);
        validateSemantics(scope, phaseRef); limitSubject=subject; applicationScope=scope; salesPhaseRef=phaseRef; cancellationRefundRule=refundRule; includesPresale=presale;
        policyScopeKey=scopeKey(subject, scope, performanceId, phaseRef);
    }
    private void activateScope() { activeScopeKey=policyScopeKey; }
    private static String scopeKey(LimitSubject subject, ApplicationScope scope, UUID performanceId, String phaseRef) {
        String subjectPart=subject == null ? "LEGACY" : subject.name();
        if (scope==ApplicationScope.EVENT) return subjectPart + ":EVENT";
        if (scope==ApplicationScope.SALES_PHASE) return subjectPart + ":SALES_PHASE:" + performanceId + ":" + phaseRef;
        return subjectPart + ":PERFORMANCE:" + performanceId;
    }
    private static void validateSemantics(ApplicationScope scope, String phaseRef) {
        if (scope==ApplicationScope.SALES_PHASE && (phaseRef==null || phaseRef.isBlank() || phaseRef.length()>128)) throw new IllegalArgumentException("Invalid sales phase");
        if (scope!=ApplicationScope.SALES_PHASE && phaseRef!=null) throw new IllegalArgumentException("salesPhaseRef requires SALES_PHASE scope");
    }
    private static String required(String value, int max) { if (value==null || value.isBlank() || value.length()>max) throw new IllegalArgumentException("Invalid policy value"); return value; }
}
