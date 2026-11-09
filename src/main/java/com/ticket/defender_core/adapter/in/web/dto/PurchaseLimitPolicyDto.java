package com.ticket.defender_core.adapter.in.web.dto;
import com.ticket.defender_core.domain.PurchaseLimitPolicy; import java.time.LocalDateTime; import java.util.UUID;
public final class PurchaseLimitPolicyDto {
    private PurchaseLimitPolicyDto() { }
    public record Create(UUID performanceId, int maxTicketsPerAccount, String policyVersion, LocalDateTime effectiveFrom, LocalDateTime effectiveUntil, String policySource, String verificationBasis,
                         PurchaseLimitPolicy.LimitSubject limitSubject, PurchaseLimitPolicy.ApplicationScope applicationScope, String salesPhaseRef, PurchaseLimitPolicy.CancellationRefundRule cancellationRefundRule, Boolean includesPresale) { }
    public record Revise(int expectedRevision, int maxTicketsPerAccount, String policyVersion, LocalDateTime effectiveFrom, LocalDateTime effectiveUntil, String policySource, String verificationBasis,
                         PurchaseLimitPolicy.LimitSubject limitSubject, PurchaseLimitPolicy.ApplicationScope applicationScope, String salesPhaseRef, PurchaseLimitPolicy.CancellationRefundRule cancellationRefundRule, Boolean includesPresale) { }
    public record Withdraw(int expectedRevision) { }
    public record View(UUID policyId, UUID eventId, UUID performanceId, int maxTicketsPerAccount, String policyVersion, LocalDateTime effectiveFrom, LocalDateTime effectiveUntil, PurchaseLimitPolicy.Status status, String policySource, String verificationBasis,
                       PurchaseLimitPolicy.LimitSubject limitSubject, PurchaseLimitPolicy.ApplicationScope applicationScope, String salesPhaseRef, PurchaseLimitPolicy.CancellationRefundRule cancellationRefundRule, Boolean includesPresale, int revision, LocalDateTime changedAt) { }
}
