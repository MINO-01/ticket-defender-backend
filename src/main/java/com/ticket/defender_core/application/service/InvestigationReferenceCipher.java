package com.ticket.defender_core.application.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

/** 참조번호 3종만 별도 키로 암호화합니다. HMAC 키와 공유하지 않으며 기능은 기본 비활성입니다. */
@Component
public final class InvestigationReferenceCipher {
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int MAX_REFERENCE_BYTES = 2048;
    private static final String KEY_UNAVAILABLE = "INVESTIGATION_REVEAL_KEY_UNAVAILABLE";
    private final ObjectProvider<InvestigationRevealKeySource> kms;
    private final boolean enabled;
    private final String keySource;
    private final String syntheticMasterBase64;
    private final String syntheticKeyVersion;
    private final SecureRandom random = new SecureRandom();

    public InvestigationReferenceCipher() {
        this.kms = null;
        this.enabled = false;
        this.keySource = "SYNTHETIC_CONFIG";
        this.syntheticMasterBase64 = "";
        this.syntheticKeyVersion = "SYNTHETIC_V1";
    }

    @Autowired
    public InvestigationReferenceCipher(ObjectProvider<InvestigationRevealKeySource> kms,
            @Value("${ticket-defender.investigation-reveal.enabled:false}") boolean enabled,
            @Value("${ticket-defender.investigation-reveal.key-source:SYNTHETIC_CONFIG}") String keySource,
            @Value("${ticket-defender.investigation-reveal.synthetic-master-key-base64:}") String syntheticMasterBase64,
            @Value("${ticket-defender.investigation-reveal.synthetic-key-version:SYNTHETIC_V1}") String syntheticKeyVersion) {
        this.kms = kms;
        this.enabled = enabled;
        this.keySource = keySource == null ? "" : keySource.strip().toUpperCase(Locale.ROOT);
        this.syntheticMasterBase64 = syntheticMasterBase64 == null ? "" : syntheticMasterBase64.strip();
        this.syntheticKeyVersion = syntheticKeyVersion == null ? "" : syntheticKeyVersion.strip();
        if (!"SYNTHETIC_CONFIG".equals(this.keySource) && !"KMS".equals(this.keySource))
            throw new IllegalStateException("ticket-defender.investigation-reveal.key-source must be SYNTHETIC_CONFIG or KMS");
    }

    /** Null means the optional feature is off or its selected key provider is unavailable. */
    public Encrypted encrypt(UUID importId, UUID tenantId, UUID eventId, UUID performanceId, long rowNumber,
            String ticketHmac, String reservationHmac, String accountHmac,
            String accountReference, String reservationReference, String ticketReference) {
        if (!enabled) return null;
        String version = currentVersion();
        byte[] key = key(tenantId, version);
        if (key == null) return null;
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        byte[] plaintext = null;
        try {
            plaintext = encode(accountReference, reservationReference, ticketReference);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(importId, tenantId, eventId, performanceId, rowNumber, ticketHmac, reservationHmac, accountHmac));
            return new Encrypted(version, nonce, cipher.doFinal(plaintext));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("INVESTIGATION_REFERENCE_ENCRYPTION_FAILED");
        } finally {
            java.util.Arrays.fill(key, (byte) 0);
            if (plaintext != null) java.util.Arrays.fill(plaintext, (byte) 0);
        }
    }

    public References decrypt(UUID importId, UUID tenantId, UUID eventId, UUID performanceId, long rowNumber,
            String ticketHmac, String reservationHmac, String accountHmac, Encrypted encrypted) {
        if (!enabled) throw new CaseApiException(HttpStatus.SERVICE_UNAVAILABLE, "INVESTIGATION_REVEAL_DISABLED");
        String current = currentVersion();
        if (current == null || !current.equals(encrypted.keyVersion()))
            throw new CaseApiException(HttpStatus.CONFLICT, "INVESTIGATION_REVEAL_KEY_VERSION_UNAVAILABLE");
        byte[] key = key(tenantId, encrypted.keyVersion());
        if (key == null) throw new CaseApiException(HttpStatus.SERVICE_UNAVAILABLE, KEY_UNAVAILABLE);
        byte[] plaintext = null;
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, encrypted.nonce()));
            cipher.updateAAD(aad(importId, tenantId, eventId, performanceId, rowNumber, ticketHmac, reservationHmac, accountHmac));
            plaintext = cipher.doFinal(encrypted.ciphertext());
            return decode(plaintext);
        } catch (GeneralSecurityException | java.io.IOException | IllegalArgumentException failure) {
            throw new CaseApiException(HttpStatus.CONFLICT, "INVESTIGATION_REVEAL_CIPHERTEXT_INVALID");
        } finally {
            java.util.Arrays.fill(key, (byte) 0);
            if (plaintext != null) java.util.Arrays.fill(plaintext, (byte) 0);
        }
    }

    public boolean enabled() { return enabled; }
    public boolean syntheticConfigSource() { return "SYNTHETIC_CONFIG".equals(keySource); }

    public void requireAvailableForTenant(UUID tenantId) {
        if (!enabled) throw new CaseApiException(HttpStatus.SERVICE_UNAVAILABLE, "INVESTIGATION_REVEAL_DISABLED");
        String version = currentVersion();
        byte[] resolved = version == null ? null : key(tenantId, version);
        if (resolved == null) throw new CaseApiException(HttpStatus.SERVICE_UNAVAILABLE, KEY_UNAVAILABLE);
        if (resolved != null) java.util.Arrays.fill(resolved, (byte) 0);
    }

    private String currentVersion() {
        String version;
        if ("KMS".equals(keySource)) {
            InvestigationRevealKeySource source = kms == null ? null : kms.getIfAvailable();
            try { version = source == null ? null : source.currentKeyVersion(); }
            catch (RuntimeException unavailable) { version = null; }
        } else {
            version = validSyntheticMaster() ? syntheticKeyVersion : null;
        }
        return version != null && version.matches("[A-Za-z0-9._-]{1,64}") ? version : null;
    }

    private byte[] key(UUID tenantId, String version) {
        if (tenantId == null || version == null || version.isBlank()) return null;
        if ("KMS".equals(keySource)) {
            InvestigationRevealKeySource source = kms == null ? null : kms.getIfAvailable();
            byte[] value;
            try { value = source == null ? null : source.tenantDataKey(tenantId, version); }
            catch (RuntimeException unavailable) { return null; }
            return value != null && value.length == 32 ? value.clone() : null;
        }
        if (!syntheticKeyVersion.equals(version)) return null;
        byte[] master = decodeMaster();
        if (master == null) return null;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(master, "HmacSHA256"));
            mac.update("ticket-defender:investigation-reference:aes-256-gcm:v1:".getBytes(StandardCharsets.US_ASCII));
            mac.update(tenantId.toString().getBytes(StandardCharsets.US_ASCII));
            return mac.doFinal();
        } catch (GeneralSecurityException failure) {
            return null;
        } finally {
            java.util.Arrays.fill(master, (byte) 0);
        }
    }

    private boolean validSyntheticMaster() {
        byte[] master = decodeMaster();
        if (master == null) return false;
        java.util.Arrays.fill(master, (byte) 0);
        return !syntheticKeyVersion.isBlank();
    }
    private byte[] decodeMaster() {
        try {
            byte[] master = Base64.getDecoder().decode(syntheticMasterBase64);
            if (master.length != 32) { java.util.Arrays.fill(master, (byte) 0); return null; }
            return master;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static byte[] aad(UUID importId, UUID tenantId, UUID eventId, UUID performanceId, long rowNumber,
            String ticketHmac, String reservationHmac, String accountHmac) {
        String value = "TD-INVESTIGATION-REFERENCE-v1|" + importId + "|" + tenantId + "|" + eventId + "|"
                + performanceId + "|" + rowNumber + "|" + ticketHmac + "|" + reservationHmac + "|" + accountHmac;
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] encode(String account, String reservation, String ticket) {
        try {
            WipingByteArrayOutputStream bytes = new WipingByteArrayOutputStream();
            try {
                try (DataOutputStream output = new DataOutputStream(bytes)) {
                    write(output, account); write(output, reservation); write(output, ticket);
                }
                return bytes.copyAndClear();
            } finally {
                bytes.clear();
            }
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException("INVESTIGATION_REFERENCE_ENCODING_FAILED");
        }
    }

    private static void write(DataOutputStream output, String value) throws java.io.IOException {
        if (value == null) { output.writeInt(-1); return; }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        try {
            if (bytes.length > MAX_REFERENCE_BYTES) throw new IllegalArgumentException("INVESTIGATION_REFERENCE_TOO_LONG");
            output.writeInt(bytes.length); output.write(bytes);
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    private static final class WipingByteArrayOutputStream extends ByteArrayOutputStream {
        private byte[] copyAndClear() {
            byte[] result = toByteArray();
            clear();
            return result;
        }
        private void clear() {
            java.util.Arrays.fill(buf, (byte) 0);
            reset();
        }
    }

    private static References decode(byte[] plaintext) throws java.io.IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(plaintext))) {
            References values = new References(read(input), read(input), read(input));
            if (input.available() != 0) throw new IllegalArgumentException("trailing encrypted reference bytes");
            return values;
        }
    }

    private static String read(DataInputStream input) throws java.io.IOException {
        int length = input.readInt();
        if (length == -1) return null;
        if (length < 0 || length > MAX_REFERENCE_BYTES || length > input.available())
            throw new IllegalArgumentException("invalid encrypted reference length");
        byte[] bytes = input.readNBytes(length);
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException invalid) {
            throw new IllegalArgumentException("invalid encrypted reference encoding");
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    public record References(String accountReference, String reservationReference, String ticketReference) {
        @Override public String toString() { return "References[redacted]"; }
    }
    public static final class Encrypted {
        private final String keyVersion;
        private final byte[] nonce;
        private final byte[] ciphertext;
        public Encrypted(String keyVersion, byte[] nonce, byte[] ciphertext) {
            this.keyVersion = keyVersion; this.nonce = nonce.clone(); this.ciphertext = ciphertext.clone();
        }
        public String keyVersion() { return keyVersion; }
        public byte[] nonce() { return nonce.clone(); }
        public byte[] ciphertext() { return ciphertext.clone(); }
        @Override public String toString() { return "Encrypted[redacted]"; }
    }
}
