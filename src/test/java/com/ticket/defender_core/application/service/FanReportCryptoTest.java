package com.ticket.defender_core.application.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FanReportCryptoTest {
    @Test
    void lookupSecretUsesOneWayDomainSeparatedDigest() {
        FanReportCrypto crypto = new FanReportCrypto(true,
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        crypto.initialize();
        String receipt = crypto.newReceiptNumber();
        String code = crypto.newLookupCode();
        String digest = crypto.lookupDigest(receipt, code);
        assertThat(receipt).startsWith("FR-").hasSize(27);
        assertThat(code).hasSize(43);
        assertThat(digest).hasSize(64).doesNotContain(code);
        assertThat(crypto.verifyLookup(receipt, code, digest)).isTrue();
        assertThat(crypto.verifyLookup(receipt, "wrong", digest)).isFalse();
        assertThat(crypto.verifyLookup("different", code, digest)).isFalse();
        assertThat(crypto.idempotencyDigest(code)).isNotEqualTo(digest);
    }

    @Test
    void publicModeCannotStartWithMissingOrShortKey() {
        assertThatThrownBy(() -> new FanReportCrypto(true, "").initialize())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new FanReportCrypto(true, "AA==").initialize())
                .isInstanceOf(IllegalStateException.class);
    }
}
