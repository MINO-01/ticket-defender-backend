package com.ticket.defender_core.application.service;

import com.ticket.defender_core.domain.PurchaseLimitPolicy;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Selects only an explicit, current customer policy; it never derives a person identity from graph evidence. */
public final class PurchaseLimitPolicySelector {
    private PurchaseLimitPolicySelector() { }
    public enum Status { SELECTED, POLICY_NOT_CONFIRMED }
    public record Context(UUID performanceId, String salesPhaseRef, PurchaseLimitPolicy.LimitSubject subject,
                          boolean personIdentityBasisAuthorized, LocalDateTime evaluatedAt) { }
    public record Result(Status status, PurchaseLimitPolicy policy, String reason) { }

    public static Result select(List<PurchaseLimitPolicy> policies, Context context) {
        if (context==null || context.performanceId()==null || context.subject()==null || context.evaluatedAt()==null)
            return unavailable("Required policy selection context is absent");
        if (context.subject()==PurchaseLimitPolicy.LimitSubject.PERSON && !context.personIdentityBasisAuthorized())
            return unavailable("PERSON identity basis is not customer-authorized");
        List<PurchaseLimitPolicy> eligible = policies==null ? List.of() : policies.stream()
                .filter(policy -> policy.getStatus()==PurchaseLimitPolicy.Status.ACTIVE)
                .filter(PurchaseLimitPolicy::hasConfirmedLimitSemantics)
                .filter(policy -> policy.getLimitSubject()==context.subject())
                .filter(policy -> inEffectiveWindow(policy, context.evaluatedAt()))
                .filter(policy -> applies(policy, context)).toList();
        if (eligible.isEmpty()) return unavailable("No confirmed policy applies");
        int highest = eligible.stream().mapToInt(PurchaseLimitPolicySelector::precedence).max().orElseThrow();
        List<PurchaseLimitPolicy> winners=eligible.stream().filter(policy -> precedence(policy)==highest).toList();
        if (winners.size()!=1) return unavailable("Conflicting confirmed policies apply at the same scope");
        return new Result(Status.SELECTED,winners.getFirst(),"Explicit customer policy selected");
    }
    private static boolean applies(PurchaseLimitPolicy policy, Context context) {
        return switch(policy.getApplicationScope()) {
            case EVENT -> true;
            case PERFORMANCE -> context.performanceId().equals(policy.getPerformanceId());
            case SALES_PHASE -> context.performanceId().equals(policy.getPerformanceId())
                    && policy.getSalesPhaseRef().equals(context.salesPhaseRef());
        };
    }
    private static boolean inEffectiveWindow(PurchaseLimitPolicy policy, LocalDateTime at) {
        return !at.isBefore(policy.getEffectiveFrom()) && (policy.getEffectiveUntil()==null || at.isBefore(policy.getEffectiveUntil()));
    }
    private static int precedence(PurchaseLimitPolicy policy) { return switch(policy.getApplicationScope()) { case EVENT -> 1; case PERFORMANCE -> 2; case SALES_PHASE -> 3; }; }
    private static Result unavailable(String reason) { return new Result(Status.POLICY_NOT_CONFIRMED,null,reason); }
}
