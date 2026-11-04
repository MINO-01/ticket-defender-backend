package com.ticket.defender_core.application.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** 후보 대조 명령의 canonical digest 및 보수적 좌석 정규화 규칙입니다. */
final class CandidatePipelineSupport {
    private static final Pattern UUID_V4 = Pattern.compile(
            "(?i)[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

    private CandidatePipelineSupport() { }

    static String idempotencyDigest(String value) {
        if (value == null || !UUID_V4.matcher(value).matches())
            throw new CaseApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY");
        return sha256(value.toLowerCase(Locale.ROOT));
    }

    static String sha256(String... parts) {
        StringBuilder canonical = new StringBuilder();
        for (String part : parts) {
            String required = Objects.requireNonNull(part);
            canonical.append(required.length()).append(':').append(required);
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** 앞뒤 공백만 제거한다. 대소문자/문자폭/좌석 표기를 추정하거나 보정하지 않는다. */
    static String seat(String value) {
        if (value == null) throw invalid();
        String normalized = value.strip();
        if (normalized.isBlank() || normalized.length() > 128) throw invalid();
        return normalized;
    }

    static String bounded(String value, int max) {
        if (value == null) throw invalid();
        String normalized = value.strip();
        if (normalized.isBlank() || normalized.length() > max) throw invalid();
        return normalized;
    }

    static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException invalid) {
            throw new CaseApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        }
    }

    static CaseApiException invalid() {
        return new CaseApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED");
    }
}
