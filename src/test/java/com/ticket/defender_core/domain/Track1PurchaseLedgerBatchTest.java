package com.ticket.defender_core.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class Track1PurchaseLedgerBatchTest {
    @Test
    void acceptedBatchRetainsThePrivateTrack2CorrelationDigestForRunIntegrityVerification() {
        Track1PurchaseLedgerBatch batch = batch(Track1PurchaseLedgerBatch.Status.ACCEPTED, "b".repeat(64));

        assertThat(batch.getTrack2LinkDigest()).isEqualTo("b".repeat(64));
    }

    @Test
    void rejectedBatchDoesNotClaimToHaveATrack2CorrelationDigest() {
        Track1PurchaseLedgerBatch batch = batch(Track1PurchaseLedgerBatch.Status.REJECTED, null);

        assertThat(batch.getTrack2LinkDigest()).isNull();
    }

    private static Track1PurchaseLedgerBatch batch(Track1PurchaseLedgerBatch.Status status, String track2LinkDigest) {
        UUID scope = UUID.randomUUID();
        return new Track1PurchaseLedgerBatch(new Track1PurchaseLedgerBatch.Spec(scope, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64), "c".repeat(64), "d".repeat(64),
                track2LinkDigest, Track1PurchaseLedgerBatch.InputType.INDIVIDUAL_TICKET, "synthetic-ledger-v1",
                LocalDateTime.of(2026, 11, 1, 0, 0), 1, "PROVIDER_TICKET_LEDGER", "v1", scope,
                true, status, 1, status == Track1PurchaseLedgerBatch.Status.ACCEPTED ? 1 : 0,
                status == Track1PurchaseLedgerBatch.Status.ACCEPTED ? 0 : 1, LocalDateTime.of(2026, 11, 1, 0, 0)));
    }
}
