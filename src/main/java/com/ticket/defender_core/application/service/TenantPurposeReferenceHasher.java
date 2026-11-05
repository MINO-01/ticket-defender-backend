package com.ticket.defender_core.application.service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * 원본 예매번호를 저장하지 않기 위한 tenant·목적·키 버전 분리 HMAC입니다.
 * HMAC 값 자체도 재식별 위험이 있으므로 API와 로그에 노출하지 않습니다.
 */
public final class TenantPurposeReferenceHasher {
    private static final String ALGORITHM = "HmacSHA256";
    public static final String RESERVATION_REFERENCE_PURPOSE = "TRACK2_RESERVATION_REFERENCE";

    private final byte[] masterKey;
    private final String purpose;
    private final String keyVersion;

    public TenantPurposeReferenceHasher(String masterKey, String keyVersion) {
        this(masterKey, RESERVATION_REFERENCE_PURPOSE, keyVersion);
    }

    TenantPurposeReferenceHasher(String masterKey, String purpose, String keyVersion) {
        if (masterKey == null || masterKey.length() < 32)
            throw new IllegalArgumentException("A synthetic HMAC master key of at least 32 characters is required");
        if (purpose == null || purpose.isBlank() || purpose.length() > 64)
            throw new IllegalArgumentException("A HMAC purpose is required");
        if (keyVersion == null || keyVersion.isBlank() || keyVersion.length() > 32)
            throw new IllegalArgumentException("A HMAC key version is required");
        this.masterKey = masterKey.getBytes(StandardCharsets.UTF_8);
        this.purpose = purpose;
        this.keyVersion = keyVersion;
    }

    public String keyVersion() {
        return keyVersion;
    }

    public String purpose() {
        return purpose;
    }

    public String hash(UUID tenantId, String reservationReference) {
        Objects.requireNonNull(tenantId, "tenantId");
        if (reservationReference == null || reservationReference.isBlank())
            throw new IllegalArgumentException("reservationReference is required");
        byte[] tenantPurposeKey = hmac(masterKey, canonical("derive", purpose, keyVersion, tenantId.toString()));
        return HexFormat.of().formatHex(hmac(tenantPurposeKey,
                canonical("reference", purpose, keyVersion, tenantId.toString(), reservationReference)));
    }

    private static byte[] hmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HMAC-SHA-256 is unavailable", impossible);
        }
    }

    private static String canonical(String... parts) {
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            String required = Objects.requireNonNull(part);
            result.append(required.length()).append(':').append(required);
        }
        return result.toString();
    }
}
