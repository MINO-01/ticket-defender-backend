package com.ticket.defender_core.application.service;

import java.security.MessageDigest;
import java.util.HexFormat;

/** 원본 이미지 바이트의 실제 형식과 SHA-256을 확인하는 공용 규칙입니다. Listing 분석과 관리자 원본 열람이 같은 규칙을 씁니다. */
final class EvidenceImageBytes {
    private EvidenceImageBytes() {
    }

    /** 파일 앞부분 서명으로 PNG와 JPEG만 판별합니다. 그 밖의 형식은 null입니다. */
    static String sniff(byte[] bytes) {
        if (bytes.length >= 8 && (bytes[0] & 255) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
                && bytes[4] == 13 && bytes[5] == 10 && bytes[6] == 26 && bytes[7] == 10) return "image/png";
        if (bytes.length >= 3 && (bytes[0] & 255) == 0xff && (bytes[1] & 255) == 0xd8 && (bytes[2] & 255) == 0xff)
            return "image/jpeg";
        return null;
    }

    static boolean sameDigest(String actual, String expected) {
        try {
            return MessageDigest.isEqual(HexFormat.of().parseHex(actual), HexFormat.of().parseHex(expected));
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
