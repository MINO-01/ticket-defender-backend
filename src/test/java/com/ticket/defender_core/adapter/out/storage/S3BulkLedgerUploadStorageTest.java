package com.ticket.defender_core.adapter.out.storage;

import com.ticket.defender_core.application.service.BulkLedgerUploadStorage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class S3BulkLedgerUploadStorageTest {
    @Test
    void dataManifestObjectNameMapsToItsConfiguredPrivatePrefix() {
        assertThat(S3BulkLedgerUploadStorage.bulkObjectKey("track1-ledgers/", "bl07-artifact"))
                .isEqualTo("track1-ledgers/bl07-artifact");
    }

    @Test
    void blankPrefixOrNonOpaqueObjectNameIsRejectedRatherThanRewritten() {
        assertThatThrownBy(() -> S3BulkLedgerUploadStorage.bulkObjectKey("", "bl07-artifact"))
                .isInstanceOf(BulkLedgerUploadStorage.InvalidObject.class);
        assertThatThrownBy(() -> S3BulkLedgerUploadStorage.bulkObjectKey("track1-ledgers", "nested/key"))
                .isInstanceOf(BulkLedgerUploadStorage.InvalidObject.class);
    }
}
