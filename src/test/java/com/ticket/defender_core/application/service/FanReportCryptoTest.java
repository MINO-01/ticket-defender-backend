package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.FanReportSubmission;
import com.ticket.defender_core.domain.FanReport;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FanReportCryptoTest {
    private static final String RECOVERY_SECRET = "A".repeat(43);
    private static final String IDEMPOTENCY_KEY = "11111111-1111-4111-8111-111111111111";

    @Test
    void lookupSecretUsesOneWayDomainSeparatedDigest() {
        FanReportCrypto crypto = new FanReportCrypto(true,
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        crypto.initialize();
        String receipt = crypto.newReceiptNumber();
        byte[] recoveryKey = crypto.recoveryKey(RECOVERY_SECRET);
        String code = crypto.lookupCode(recoveryKey, IDEMPOTENCY_KEY, receipt);
        String digest = crypto.lookupDigest(receipt, code);
        assertThat(receipt).startsWith("FR-").hasSize(27);
        assertThat(code).hasSize(43);
        assertThat(digest).hasSize(64).doesNotContain(code);
        assertThat(crypto.verifyLookup(receipt, code, digest)).isTrue();
        assertThat(crypto.verifyLookup(receipt, "wrong", digest)).isFalse();
        assertThat(crypto.verifyLookup("different", code, digest)).isFalse();
        assertThat(crypto.idempotencyDigest(code)).isNotEqualTo(digest);
        assertThat(crypto.lookupCode(crypto.recoveryKey(RECOVERY_SECRET), IDEMPOTENCY_KEY, receipt)).isEqualTo(code);
        assertThat(crypto.lookupCode(crypto.recoveryKey("B".repeat(42) + "A"), IDEMPOTENCY_KEY, receipt))
                .isNotEqualTo(code);
        assertThat(crypto.lookupCode(recoveryKey, "22222222-2222-4222-8222-222222222222", receipt))
                .isNotEqualTo(code);
        assertThat(crypto.lookupCode(recoveryKey, IDEMPOTENCY_KEY, "different")).isNotEqualTo(code);
        assertThatThrownBy(() -> crypto.recoveryKey("short"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> crypto.recoveryKey("A".repeat(42) + "B"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reportCredentialsRemainValidWhenQuotaKeyRotates() {
        FanReportCrypto first = new FanReportCrypto(true, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        FanReportCrypto rotated = new FanReportCrypto(true, "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=");
        first.initialize();
        rotated.initialize();
        String receipt = first.newReceiptNumber();
        byte[] secret = first.recoveryKey(RECOVERY_SECRET);
        String code = first.lookupCode(secret, IDEMPOTENCY_KEY, receipt);
        assertThat(rotated.lookupCode(rotated.recoveryKey(RECOVERY_SECRET), IDEMPOTENCY_KEY, receipt)).isEqualTo(code);
        assertThat(rotated.verifyLookup(receipt, code, first.lookupDigest(receipt, code))).isTrue();
        assertThat(rotated.idempotencyDigest("11111111-1111-4111-8111-111111111111"))
                .isEqualTo(first.idempotencyDigest("11111111-1111-4111-8111-111111111111"));
        FanReportSubmission request = new FanReportSubmission(UUID.randomUUID(),
                LocalDateTime.parse("2026-11-01T19:30:00"), null, FanReport.ReportType.OTHER,
                "Synthetic report description for key rotation", null, null, null, null);
        assertThat(rotated.requestDigest(request, List.of(), rotated.recoveryKey(RECOVERY_SECRET)))
                .isEqualTo(first.requestDigest(request, List.of(), secret));
        assertThat(rotated.actorDigest("synthetic-source")).isNotEqualTo(first.actorDigest("synthetic-source"));
    }

    @Test
    void publicModeCannotStartWithMissingOrShortKey() {
        assertThatThrownBy(() -> new FanReportCrypto(true, "").initialize())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new FanReportCrypto(true, "AA==").initialize())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void existingOneTimeLookupCodeRemainsUsableWithOriginalServerKey() {
        FanReportCrypto original = new FanReportCrypto(true, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        FanReportCrypto rotated = new FanReportCrypto(true, "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=");
        original.initialize();
        rotated.initialize();
        String receipt = original.newReceiptNumber();
        String oldCode = "synthetic-existing-random-code";
        String oldDigest = original.legacyLookupDigest(receipt, oldCode);
        assertThat(original.verifyLookup(receipt, oldCode, oldDigest)).isTrue();
        assertThat(rotated.verifyLookup(receipt, oldCode, oldDigest)).isFalse();
    }
}
