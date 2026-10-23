package com.ticket.defender_core.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FanReportStorageConfigTest {
    private final FanReportStorageConfig config = new FanReportStorageConfig();

    @Test
    void enabledIntakeRequiresPrivateStorageConfiguration() {
        assertThatThrownBy(() -> config.evidenceObjectStorage(true, "", "us-east-1", "", "", "", "", ""))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> config.evidenceObjectStorage(true, "http://storage.example.test:9000",
                "us-east-1", "private", "synthetic", "synthetic", "", ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("HTTPS");
    }

    @Test
    void incompleteMetadataCredentialsFailClosedEvenWhenIntakeIsDisabled() {
        assertThatThrownBy(() -> config.evidenceObjectStorage(false, "https://storage.example.test",
                "us-east-1", "private", "", "", "synthetic", ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("metadata credentials");
    }

    @Test
    void disabledPublicIntakeMayStillConfigurePrivateMetadataLookup() {
        var storage = config.evidenceObjectStorage(false, "http://127.0.0.1:19000",
                "us-east-1", "private", "", "", "synthetic-head", "synthetic-head-secret");
        assertThat(storage).isInstanceOf(com.ticket.defender_core.adapter.out.storage.S3EvidenceObjectStorage.class);
        assertThatThrownBy(() -> storage.delete("synthetic", null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("write is disabled");
    }
}
