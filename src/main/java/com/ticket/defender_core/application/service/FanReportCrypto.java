package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.FanReportSubmission;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

@Component
public class FanReportCrypto {
    private final boolean enabled;
    private final String configuredKey;
    private final SecureRandom random = new SecureRandom();
    private byte[] key;

    public FanReportCrypto(@Value("${ticket-defender.fan-report.enabled:false}") boolean enabled,
                           @Value("${ticket-defender.fan-report.hmac-key-base64:}") String configuredKey) {
        this.enabled = enabled;
        this.configuredKey = configuredKey;
    }

    @PostConstruct
    void initialize() {
        if (configuredKey == null || configuredKey.isBlank()) {
            if (enabled) throw new IllegalStateException("Fan report HMAC key is required when public intake is enabled");
            key = random.generateSeed(32);
            return;
        }
        try {
            key = Base64.getDecoder().decode(configuredKey);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("Fan report HMAC key must be Base64", invalid);
        }
        if (key.length < 32) throw new IllegalStateException("Fan report HMAC key must be at least 32 bytes");
    }

    public String newReceiptNumber() { return "FR-" + randomToken(18); }
    /** The recovery secret is independent of the idempotency key and must be retained by the client. */
    public byte[] recoveryKey(String suppliedSecret) {
        if (suppliedSecret == null || !suppliedSecret.matches("[A-Za-z0-9_-]{43}"))
            throw new IllegalArgumentException("Invalid recovery secret");
        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(suppliedSecret);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid recovery secret");
        }
        if (decoded.length != 32 || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(suppliedSecret))
            throw new IllegalArgumentException("Invalid recovery secret");
        return decoded;
    }

    public String lookupCode(byte[] recoveryKey, String idempotencyKey, String receipt) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                hmacBytes(recoveryKey, "lookup-code",
                        (idempotencyKey + "\u0000" + receipt).getBytes(StandardCharsets.UTF_8)));
    }

    private String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public String idempotencyDigest(String suppliedKey) { return sha256("idempotency", suppliedKey); }
    public String lookupDigest(String receipt, String code) { return sha256("lookup", receipt + "\u0000" + code); }
    public String actorDigest(String value) { return hmac("quota", value); }
    String legacyIdempotencyDigest(String suppliedKey) { return hmac("idempotency", suppliedKey); }
    String legacyLookupDigest(String receipt, String code) { return hmac("lookup", receipt + "\u0000" + code); }

    public boolean verifyLookup(String receipt, String code, String storedDigest) {
        byte[] actual = HexFormat.of().parseHex(lookupDigest(receipt, code));
        byte[] legacy = HexFormat.of().parseHex(legacyLookupDigest(receipt, code));
        byte[] stored = HexFormat.of().parseHex(storedDigest);
        return MessageDigest.isEqual(actual, stored) | MessageDigest.isEqual(legacy, stored);
    }

    public String requestDigest(FanReportSubmission request, List<ValidatedImage> images, byte[] recoveryKey) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(buffer);
            field(out, request.eventId().toString());
            field(out, request.performanceAt().toString());
            field(out, request.seatArea());
            field(out, request.reportType().name());
            field(out, request.description());
            field(out, request.listingUrl());
            field(out, request.platform());
            field(out, request.seller());
            field(out, request.askingPrice());
            out.writeInt(images.size());
            for (ValidatedImage image : images) {
                field(out, image.sha256());
                field(out, image.mediaType());
                out.writeLong(image.byteSize());
            }
            return HexFormat.of().formatHex(hmacBytes(recoveryKey, "request", buffer.toByteArray()));
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void field(DataOutputStream out, String value) throws IOException {
        if (value == null) {
            out.writeInt(-1);
        } else {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            out.writeInt(bytes.length);
            out.write(bytes);
        }
    }

    private String hmac(String domain, String input) {
        return HexFormat.of().formatHex(hmacBytes(key, domain, input.getBytes(StandardCharsets.UTF_8)));
    }

    private static String sha256(String domain, String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(domain.getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) 0);
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to calculate request digest", error);
        }
    }

    private static byte[] hmacBytes(byte[] secret, String domain, byte[] input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update(domain.getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) 0);
            return mac.doFinal(input);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to calculate request digest", error);
        }
    }
}
