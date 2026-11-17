package com.ticket.defender_core.application.service;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BulkLedgerTrack1OutboxConsumerTest {
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
}
