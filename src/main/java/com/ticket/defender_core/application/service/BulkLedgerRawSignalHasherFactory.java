package com.ticket.defender_core.application.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * v3 원본 신호 HMAC 키의 출처를 고릅니다.
 * {@code SYNTHETIC_CONFIG}는 합성 격리 게이트의 설정 키로 개발과 테스트에서만 쓰고, {@code KMS}는 운영 측이
 * {@link Track1SignalKeySource} 구현을 등록해야 동작합니다.
 */
@Component
public class BulkLedgerRawSignalHasherFactory {
    static final String KEY_UNAVAILABLE = "TRACK1_SIGNAL_KEY_UNAVAILABLE";
    private final SyntheticReservationImportGate gate;
    private final ObjectProvider<Track1SignalKeySource> kms;
    private final String keySource;
    private final Track1IpNetworkClassifier classifier;

    public BulkLedgerRawSignalHasherFactory(SyntheticReservationImportGate gate, ObjectProvider<Track1SignalKeySource> kms,
            @Value("${ticket-defender.track1-signal-hmac.key-source:SYNTHETIC_CONFIG}") String keySource,
            @Value("${ticket-defender.track1.ip-network-classes:}") List<String> networkClasses) {
        this.gate = gate;
        this.kms = kms;
        this.keySource = keySource == null ? "" : keySource.strip().toUpperCase(Locale.ROOT);
        if (!"SYNTHETIC_CONFIG".equals(this.keySource) && !"KMS".equals(this.keySource))
            throw new IllegalStateException("ticket-defender.track1-signal-hmac.key-source must be SYNTHETIC_CONFIG or KMS");
        this.classifier = new Track1IpNetworkClassifier(networkClasses);
    }

    /** 호출 전에 합성 격리 범위 검사가 끝나 있어야 합니다. */
    public BulkLedgerRawSignalHashers hashers() {
        try {
            if ("KMS".equals(keySource)) {
                Track1SignalKeySource source = kms.getIfAvailable();
                if (source == null) throw unavailable();
                return new BulkLedgerRawSignalHashers(
                        new TenantPurposeReferenceHasher(source.masterKey(), BulkLedgerRawSignalHashers.DEVICE, source.keyVersion()),
                        new TenantPurposeReferenceHasher(source.masterKey(), BulkLedgerRawSignalHashers.IP, source.keyVersion()),
                        new TenantPurposeReferenceHasher(source.masterKey(), BulkLedgerRawSignalHashers.ADDRESS, source.keyVersion()),
                        new TenantPurposeReferenceHasher(source.masterKey(), BulkLedgerRawSignalHashers.PHONE, source.keyVersion()),
                        new TenantPurposeReferenceHasher(source.masterKey(), BulkLedgerRawSignalHashers.PAYMENT, source.keyVersion()),
                        new TenantPurposeReferenceHasher(source.masterKey(), BulkLedgerRawSignalHashers.REFUND_ACCOUNT, source.keyVersion()),
                        classifier);
            }
            return new BulkLedgerRawSignalHashers(gate.referenceHasher(BulkLedgerRawSignalHashers.DEVICE),
                    gate.referenceHasher(BulkLedgerRawSignalHashers.IP), gate.referenceHasher(BulkLedgerRawSignalHashers.ADDRESS),
                    gate.referenceHasher(BulkLedgerRawSignalHashers.PHONE), gate.referenceHasher(BulkLedgerRawSignalHashers.PAYMENT),
                    gate.referenceHasher(BulkLedgerRawSignalHashers.REFUND_ACCOUNT), classifier);
        } catch (IllegalArgumentException invalid) {
            // 키 원본이 예외 메시지에 들어가지 않도록 고정 코드로만 바꿉니다.
            throw unavailable();
        }
    }

    private static CaseApiException unavailable() {
        return new CaseApiException(HttpStatus.CONFLICT, KEY_UNAVAILABLE);
    }
}
