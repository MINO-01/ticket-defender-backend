package com.ticket.defender_core.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** 기존 키와 고객사 범위 키를 각각 고정 길이 SHA-256 값으로 만듭니다. */
public final class MacroEvidenceKey {

    private MacroEvidenceKey() {}

    /**
     * 각 값 앞에 UTF-8 바이트 길이를 붙여 조합의 경계를 보존합니다.
     * 마이그레이션의 백필 식도 같은 규칙을 사용해야 합니다.
     */
    public static String create(String eventId, String reservationNo, String accountId) {
        Objects.requireNonNull(eventId, "공연 식별자는 필수입니다.");
        Objects.requireNonNull(reservationNo, "예매 번호는 필수입니다.");
        Objects.requireNonNull(accountId, "계정 식별자는 필수입니다.");

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateLengthPrefixed(digest, eventId);
            updateLengthPrefixed(digest, reservationNo);
            updateLengthPrefixed(digest, accountId);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 해시를 계산할 수 없습니다.", exception);
        }
    }

    public static String create(UUID tenantId, UUID internalEventId, String reservationNo, String accountId) {
        Objects.requireNonNull(tenantId, "고객사 식별자는 필수입니다.");
        Objects.requireNonNull(internalEventId, "내부 공연 식별자는 필수입니다.");
        Objects.requireNonNull(reservationNo, "예매 번호는 필수입니다.");
        Objects.requireNonNull(accountId, "계정 식별자는 필수입니다.");

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateLengthPrefixed(digest, "tenant-scoped-v2");
            updateLengthPrefixed(digest, tenantId.toString());
            updateLengthPrefixed(digest, internalEventId.toString());
            updateLengthPrefixed(digest, reservationNo);
            updateLengthPrefixed(digest, accountId);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 해시를 계산할 수 없습니다.", exception);
        }
    }

    private static void updateLengthPrefixed(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
    }
}
