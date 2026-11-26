package com.ticket.defender_core.application.service;

import java.util.Objects;
import java.util.stream.Stream;

/**
 * v3 원본 신호별로 목적이 분리된 HMAC 묶음입니다. 같은 원본 문자열이라도 신호 종류가 다르면 다른 토큰이 됩니다.
 * 모든 신호는 하나의 키 버전을 공유해야 하며, 다른 키 버전과 섞이면 생성 시 거절합니다.
 */
public record BulkLedgerRawSignalHashers(TenantPurposeReferenceHasher device, TenantPurposeReferenceHasher ip,
                                         TenantPurposeReferenceHasher address, TenantPurposeReferenceHasher phone,
                                         TenantPurposeReferenceHasher payment, TenantPurposeReferenceHasher refundAccount,
                                         Track1IpNetworkClassifier classifier) {
    public static final String DEVICE = "TRACK1_SIG_DEVICE";
    public static final String IP = "TRACK1_SIG_IP";
    public static final String ADDRESS = "TRACK1_SIG_ADDRESS";
    public static final String PHONE = "TRACK1_SIG_PHONE";
    public static final String PAYMENT = "TRACK1_SIG_PAYMENT";
    public static final String REFUND_ACCOUNT = "TRACK1_SIG_REFUND_ACCOUNT";

    public BulkLedgerRawSignalHashers {
        Objects.requireNonNull(classifier, "classifier");
        if (Stream.of(device, ip, address, phone, payment, refundAccount).anyMatch(Objects::isNull))
            throw new IllegalArgumentException("Every raw signal hasher is required");
        if (Stream.of(ip, address, phone, payment, refundAccount).anyMatch(hasher -> !hasher.keyVersion().equals(device.keyVersion())))
            throw new IllegalArgumentException("Raw signal hashers must share one key version");
    }

    public String keyVersion() {
        return device.keyVersion();
    }
}
