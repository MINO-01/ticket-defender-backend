package com.ticket.defender_core.application.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BulkLedgerTrack1BulkWorkerTest {
    @Test
    void rejectsLedgerRowsInsteadOfSilentlyDroppingMissingSignals() {
        assertThat(BulkLedgerTrack1BulkWorker.inputFailure("ACTIVE", "key-v1", null, null,
                "source-v1", null, null, null, null, null)).isEqualTo("LEDGER_SIGNAL_MISSING");
    }

    @Test
    void rejectsMismatchedLedgerAndSignalHmacKeyVersions() {
        assertThat(BulkLedgerTrack1BulkWorker.inputFailure("ACTIVE", "key-v1", "key-v2", "TRACK1_CONNECTION",
                "source-v1", "source-v1", "a".repeat(64), null, null, null)).isEqualTo("LEDGER_HMAC_KEY_VERSION_MISMATCH");
    }

    @Test
    void rejectsUnsettledTicketStatesAndAcceptsOnlySourcedActiveRows() {
        assertThat(BulkLedgerTrack1BulkWorker.inputFailure("REFUNDED", "key-v1", "key-v1", "TRACK1_CONNECTION",
                "source-v1", "source-v1", "a".repeat(64), null, null, null)).isEqualTo("LEDGER_TICKET_STATUS_NOT_SUPPORTED");
        assertThat(BulkLedgerTrack1BulkWorker.inputFailure("ACTIVE", "key-v1", "key-v1", "TRACK1_CONNECTION",
                "source-v1", "source-v1", "a".repeat(64), null, null, null)).isNull();
    }

    @Test
    void onlyTheCurrentFenceMayRenewALongRunningLease() {
        assertThat(BulkLedgerTrack1BulkWorker.fenceUpdateAccepted(1)).isTrue();
        assertThat(BulkLedgerTrack1BulkWorker.fenceUpdateAccepted(0)).isFalse();
        assertThat(BulkLedgerTrack1BulkWorker.fenceUpdateAccepted(2)).isFalse();
    }

    @Test
    void refusesToRewriteAnS3VersionThatCannotSatisfyTheDataManifestContract() {
        assertThat(BulkLedgerTrack1BulkWorker.manifestObjectVersionCompatible("version-1._A")).isTrue();
        assertThat(BulkLedgerTrack1BulkWorker.manifestObjectVersionCompatible("version/1")).isFalse();
    }
}
