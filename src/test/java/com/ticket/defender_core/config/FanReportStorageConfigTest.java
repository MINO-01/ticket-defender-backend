package com.ticket.defender_core.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FanReportStorageConfigTest {
    private final FanReportStorageConfig config = new FanReportStorageConfig();

    @Test
    void enabledIntakeRequiresPrivateStorageConfiguration() {
        assertThatThrownBy(() -> config.evidenceObjectStorage(true, "", "us-east-1", "", "", ""))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> config.evidenceObjectStorage(true, "http://storage.example.test:9000",
                "us-east-1", "private", "synthetic", "synthetic"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("HTTPS");
    }
}
