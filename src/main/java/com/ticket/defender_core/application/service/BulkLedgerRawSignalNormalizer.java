package com.ticket.defender_core.application.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 대량 CSV v3 원본 신호 열의 정규화 규칙입니다.
 * 반환값은 HMAC 입력으로만 쓰고 저장하거나 기록하지 않습니다. 오류에는 행 번호와 코드만 남기며 입력 값을 담지 않습니다.
 */
final class BulkLedgerRawSignalNormalizer {
    static final Set<String> NETWORK_TYPES = Set.of("MOBILE_CGNAT", "RESIDENTIAL", "CORPORATE_EDU", "DATACENTER_PROXY", "UNKNOWN");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern PARENTHESIZED = Pattern.compile("\\([^()]*\\)");
    private static final Pattern UNMASKED_CARD = Pattern.compile("[0-9]{13,}");
    private static final Pattern IPV4_OCTET = Pattern.compile("0|[1-9][0-9]{0,2}");

    private BulkLedgerRawSignalNormalizer() { }

    static String device(String value) {
        return value == null ? null : value.strip();
    }

    /** 점으로 구분된 10진수 IPv4만 받습니다. 앞자리 0은 8진수 해석과 섞일 수 있어 거절합니다. */
    static String ipv4(String value, long row) {
        if (value == null) return null;
        String[] octets = value.strip().split("\\.", -1);
        if (octets.length != 4) throw failure(row, "BULK_CSV_IP_INVALID");
        StringBuilder canonical = new StringBuilder(15);
        for (int index = 0; index < 4; index++) {
            String octet = octets[index];
            if (!IPV4_OCTET.matcher(octet).matches() || Integer.parseInt(octet) > 255) throw failure(row, "BULK_CSV_IP_INVALID");
            if (index > 0) canonical.append('.');
            canonical.append(Integer.parseInt(octet));
        }
        return canonical.toString();
    }

    static String networkType(String value, long row) {
        if (value == null) return null;
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        if (!NETWORK_TYPES.contains(normalized)) throw failure(row, "BULK_CSV_IP_NETWORK_TYPE_INVALID");
        return normalized;
    }

    /** NFKC 정규화 후 괄호 안 참고항목을 지우고 연속 공백을 하나로 줄입니다. */
    static String address(String value, long row) {
        if (value == null) return null;
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        String previous;
        do {
            previous = normalized;
            normalized = PARENTHESIZED.matcher(normalized).replaceAll(" ");
        } while (!normalized.equals(previous));
        normalized = WHITESPACE.matcher(normalized).replaceAll(" ").strip();
        if (normalized.isEmpty()) throw failure(row, "BULK_CSV_ADDRESS_INVALID");
        return normalized;
    }

    /** 숫자만 남기고 국가번호 +82는 국내 접두 0으로 바꿉니다. */
    static String phone(String value, long row) {
        if (value == null) return null;
        String stripped = value.strip();
        boolean international = stripped.startsWith("+82");
        String digits = (international ? stripped.substring(3) : stripped).replaceAll("[^0-9]", "");
        if (international) digits = "0" + (digits.startsWith("0") ? digits.substring(1) : digits);
        if (digits.isEmpty() || "0".equals(digits)) throw failure(row, "BULK_CSV_PHONE_INVALID");
        return digits;
    }

    /** 공백과 하이픈을 지운 마스킹 카드번호입니다. 마스킹되지 않은 13자리 이상 연속 숫자는 거절합니다. */
    static String maskedCard(String value, long row) {
        if (value == null) return null;
        String normalized = value.replaceAll("[\\s-]", "");
        if (normalized.isEmpty()) throw failure(row, "BULK_CSV_PAYMENT_INCOMPLETE");
        if (UNMASKED_CARD.matcher(normalized).find()) throw failure(row, "BULK_CSV_PAYMENT_UNMASKED");
        return normalized;
    }

    static String upper(String value) {
        return value == null ? null : value.strip().toUpperCase(Locale.ROOT);
    }

    static String digits(String value, long row, String code) {
        if (value == null) return null;
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) throw failure(row, code);
        return digits;
    }

    private static BulkLedgerCsvValidationException failure(long row, String code) {
        return new BulkLedgerCsvValidationException(row, code);
    }
}
