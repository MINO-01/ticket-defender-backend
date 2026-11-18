package com.ticket.defender_core.application.service;

import com.ticket.defender_core.domain.PurchaseLimitPolicy;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BulkLedgerTrack1OutboxConsumerTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID EVENT = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID PERFORMANCE = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final LocalDateTime AS_OF = LocalDateTime.of(2026, 11, 9, 12, 0);

    @Test
    void retryAttemptsUseDistinctRunIdempotencyDigestsWhileRetainingOutboxIdentity() {
        UUID outbox = UUID.randomUUID();
        assertThat(BulkLedgerTrack1OutboxConsumer.attemptIdempotencyDigest(outbox, 1))
                .isNotEqualTo(BulkLedgerTrack1OutboxConsumer.attemptIdempotencyDigest(outbox, 2));
        assertThat(BulkLedgerTrack1OutboxConsumer.attemptIdempotencyDigest(outbox, 1))
                .isEqualTo(BulkLedgerTrack1OutboxConsumer.attemptIdempotencyDigest(outbox, 1));
        assertThatThrownBy(() -> BulkLedgerTrack1OutboxConsumer.attemptIdempotencyDigest(outbox, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void usesVerifiedAccountPolicyAndOnlyNetsACompletePublicSnapshot() {
        var result = BulkLedgerTrack1OutboxConsumer.decide(snapshot(true), List.of(ticket("ACTIVE"), ticket("CANCELLED")),
                List.of(policy("account-v2", PERFORMANCE, PurchaseLimitPolicy.CancellationRefundRule.NETTED)), PERFORMANCE);

        assertThat(result.policy()).isNotNull();
        assertThat(result.policy().getPolicyVersion()).isEqualTo("account-v2");
        assertThat(result.complete()).isTrue();
        assertThat(result.payload()).singleElement().extracting(BulkLedgerTrack1OutboxConsumer.Ticket::status).isEqualTo("ACTIVE");
        assertThat(result.excludedRows()).isEqualTo(1);
        assertThat(result.quantitySourceVersion()).isEqualTo("provider-v7");
    }

    @Test
    void leavesMissingOrConflictingPoliciesUnevaluated() {
        var absent = BulkLedgerTrack1OutboxConsumer.decide(snapshot(true), List.of(ticket("ACTIVE")), List.of(), PERFORMANCE);
        var conflict = BulkLedgerTrack1OutboxConsumer.decide(snapshot(true), List.of(ticket("ACTIVE")),
                List.of(policy("one", PERFORMANCE, PurchaseLimitPolicy.CancellationRefundRule.NETTED),
                        policy("two", PERFORMANCE, PurchaseLimitPolicy.CancellationRefundRule.NETTED)), PERFORMANCE);

        assertThat(absent.complete()).isFalse();
        assertThat(absent.policy()).isNull();
        assertThat(absent.reason()).isEqualTo("NO_CONFIRMED_ACCOUNT_POLICY");
        assertThat(conflict.complete()).isFalse();
        assertThat(conflict.policy()).isNull();
        assertThat(conflict.reason()).isEqualTo("NO_CONFIRMED_ACCOUNT_POLICY");
    }

    @Test
    void blocksNettedQuantityClaimWhenSnapshotOrRefundRuleIsNotConfirmed() {
        var incompleteSnapshot = BulkLedgerTrack1OutboxConsumer.decide(snapshot(false), List.of(ticket("ACTIVE")),
                List.of(policy("account-v2", PERFORMANCE, PurchaseLimitPolicy.CancellationRefundRule.NETTED)), PERFORMANCE);
        var gross = BulkLedgerTrack1OutboxConsumer.decide(snapshot(true), List.of(ticket("ACTIVE")),
                List.of(policy("account-v2", PERFORMANCE, PurchaseLimitPolicy.CancellationRefundRule.GROSS)), PERFORMANCE);
        var invalidAsOf = BulkLedgerTrack1OutboxConsumer.decide(snapshot(true), List.of(ticketAt("ACTIVE", AS_OF.plusMinutes(1))),
                List.of(policy("account-v2", PERFORMANCE, PurchaseLimitPolicy.CancellationRefundRule.NETTED)), PERFORMANCE);

        assertThat(incompleteSnapshot.policy()).isNotNull();
        assertThat(incompleteSnapshot.complete()).isFalse();
        assertThat(incompleteSnapshot.reason()).isEqualTo("PUBLIC_LEDGER_SNAPSHOT_INCOMPLETE");
        assertThat(gross.policy()).isNotNull();
        assertThat(gross.complete()).isFalse();
        assertThat(gross.reason()).isEqualTo("GROSS_RULE_NOT_SUPPORTED");
        assertThat(invalidAsOf.complete()).isFalse();
        assertThat(invalidAsOf.reason()).isEqualTo("PUBLIC_LEDGER_SOURCE_AS_OF_INVALID");
    }

    @Test
    void neverSelectsAPolicyAnchoredToAnotherPerformance() {
        var result = BulkLedgerTrack1OutboxConsumer.decide(snapshot(true), List.of(ticket("ACTIVE")),
                List.of(policy("other-performance", UUID.randomUUID(), PurchaseLimitPolicy.CancellationRefundRule.NETTED)), PERFORMANCE);

        assertThat(result.policy()).isNull();
        assertThat(result.complete()).isFalse();
        assertThat(result.reason()).isEqualTo("NO_CONFIRMED_ACCOUNT_POLICY");
    }

    @Test
    void leavesPolicyUnconfirmedWhenEveryPurchasePredatesItsEffectiveStart() {
        var result = BulkLedgerTrack1OutboxConsumer.decide(snapshot(true), List.of(ticketAt("ACTIVE", AS_OF.minusHours(2))),
                List.of(policy("future-policy", PERFORMANCE, PurchaseLimitPolicy.CancellationRefundRule.NETTED, AS_OF.minusHours(1), null)), PERFORMANCE);

        assertThat(result.policy()).isNull();
        assertThat(result.complete()).isFalse();
        assertThat(result.reason()).isEqualTo("NO_CONFIRMED_ACCOUNT_POLICY");
    }

    @Test
    void leavesPolicyUnconfirmedWhenPurchasesRequireDifferentPolicyPeriods() {
        var result = BulkLedgerTrack1OutboxConsumer.decide(snapshot(true),
                List.of(ticketAt("ACTIVE", AS_OF.minusHours(2)), ticketAt("REFUNDED", AS_OF.minusMinutes(15))),
                List.of(policy("early-policy", PERFORMANCE, PurchaseLimitPolicy.CancellationRefundRule.NETTED,
                                AS_OF.minusDays(1), AS_OF.minusHours(1)),
                        policy("late-policy", PERFORMANCE, PurchaseLimitPolicy.CancellationRefundRule.NETTED,
                                AS_OF.minusHours(1), null)), PERFORMANCE);

        assertThat(result.policy()).isNull();
        assertThat(result.complete()).isFalse();
        assertThat(result.reason()).isEqualTo("NO_CONFIRMED_ACCOUNT_POLICY");
    }

    private static BulkLedgerTrack1OutboxConsumer.Version snapshot(boolean complete) {
        return new BulkLedgerTrack1OutboxConsumer.Version(UUID.randomUUID(), null, 7, 0, AS_OF, complete);
    }

    private static BulkLedgerTrack1OutboxConsumer.Ticket ticket(String status) {
        return ticketAt(status, AS_OF.minusHours(1));
    }

    private static BulkLedgerTrack1OutboxConsumer.Ticket ticketAt(String status, LocalDateTime purchasedAt) {
        return new BulkLedgerTrack1OutboxConsumer.Ticket("ticket-" + status, "reservation-1", "account-1", "key-v1",
                purchasedAt, 1, "TICKET", status, "provider-v7", "a".repeat(64), null, "b".repeat(64), null,
                "key-v1", "provider-v7");
    }

    private static PurchaseLimitPolicy policy(String version, UUID performance, PurchaseLimitPolicy.CancellationRefundRule rule) {
        return policy(version, performance, rule, AS_OF.minusDays(1), null);
    }

    private static PurchaseLimitPolicy policy(String version, UUID performance, PurchaseLimitPolicy.CancellationRefundRule rule,
                                              LocalDateTime effectiveFrom, LocalDateTime effectiveUntil) {
        return new PurchaseLimitPolicy(UUID.randomUUID(), TENANT, EVENT, performance, 2, version,
                effectiveFrom, effectiveUntil, "customer", "verified", PurchaseLimitPolicy.LimitSubject.ACCOUNT,
                PurchaseLimitPolicy.ApplicationScope.PERFORMANCE, null, rule, true, UUID.randomUUID(), AS_OF);
    }
}
