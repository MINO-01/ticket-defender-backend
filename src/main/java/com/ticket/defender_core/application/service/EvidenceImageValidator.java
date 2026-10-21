package com.ticket.defender_core.application.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;

@Component
public class EvidenceImageValidator {
    public static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    public static final long MAX_REQUEST_FILE_BYTES = 5L * MAX_FILE_BYTES;
    public static final int MAX_FILES = 5;
    public static final long MAX_PIXELS = 20_000_000L;
    public static final int MAX_DIMENSION = 12_000;

    public List<ValidatedImage> validate(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) return List.of();
        if (files.size() > MAX_FILES) throw error(HttpStatus.PAYLOAD_TOO_LARGE, "TOO_MANY_FILES");
        long total = 0;
        List<ValidatedImage> result = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "EMPTY_FILE");
            if (file.getSize() > MAX_FILE_BYTES) throw error(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE");
            total += file.getSize();
            if (total > MAX_REQUEST_FILE_BYTES) throw error(HttpStatus.PAYLOAD_TOO_LARGE, "REQUEST_TOO_LARGE");
            byte[] bytes;
            try {
                bytes = file.getBytes();
            } catch (IOException unreadable) {
                throw error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_IMAGE");
            }
            if (bytes.length == 0) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "EMPTY_FILE");
            if (bytes.length > MAX_FILE_BYTES) throw error(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE");
            String type = sniff(bytes);
            checkClientMetadata(file, type);
            decode(bytes, type);
            result.add(new ValidatedImage(bytes, type, bytes.length, sha256(bytes)));
        }
        return List.copyOf(result);
    }

    private String sniff(byte[] bytes) {
        if (bytes.length >= 12 && (bytes[0] & 255) == 0x89 && bytes[1] == 'P'
                && bytes[2] == 'N' && bytes[3] == 'G' && bytes[4] == 13 && bytes[5] == 10
                && bytes[6] == 26 && bytes[7] == 10) {
            checkPngChunks(bytes);
            return "image/png";
        }
        if (bytes.length >= 4 && (bytes[0] & 255) == 0xff && (bytes[1] & 255) == 0xd8
                && (bytes[bytes.length - 2] & 255) == 0xff && (bytes[bytes.length - 1] & 255) == 0xd9)
            return "image/jpeg";
        throw error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_IMAGE_TYPE");
    }

    private void checkPngChunks(byte[] bytes) {
        int offset = 8;
        int chunks = 0;
        boolean imageHeader = false;
        boolean imageEnd = false;
        while (offset + 12 <= bytes.length && ++chunks <= 10_000) {
            long length = ((long) (bytes[offset] & 255) << 24) | ((long) (bytes[offset + 1] & 255) << 16)
                    | ((long) (bytes[offset + 2] & 255) << 8) | (bytes[offset + 3] & 255);
            long end = (long) offset + 12 + length;
            if (length > Integer.MAX_VALUE || end > bytes.length)
                throw error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_IMAGE");
            String chunk = new String(bytes, offset + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if (!imageHeader && !"IHDR".equals(chunk))
                throw error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_IMAGE");
            if ("IHDR".equals(chunk)) {
                if (imageHeader || length != 13) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_IMAGE");
                imageHeader = true;
            }
            CRC32 crc = new CRC32();
            crc.update(bytes, offset + 4, (int) length + 4);
            int checksumOffset = offset + 8 + (int) length;
            long expected = ((long) (bytes[checksumOffset] & 255) << 24)
                    | ((long) (bytes[checksumOffset + 1] & 255) << 16)
                    | ((long) (bytes[checksumOffset + 2] & 255) << 8)
                    | (bytes[checksumOffset + 3] & 255);
            if (crc.getValue() != expected) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_IMAGE");
            offset = (int) end;
            if ("IEND".equals(chunk)) {
                if (length != 0 || offset != bytes.length)
                    throw error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_IMAGE");
                imageEnd = true;
                break;
            }
        }
        if (!imageHeader || !imageEnd) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_IMAGE");
    }

    private void checkClientMetadata(MultipartFile file, String actualType) {
        String declared = file.getContentType();
        if (declared != null && !declared.equalsIgnoreCase(actualType))
            throw error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "IMAGE_TYPE_MISMATCH");
        String name = file.getOriginalFilename();
        if (name != null && !name.isBlank()) {
            String lower = name.toLowerCase(Locale.ROOT);
            boolean expected = actualType.equals("image/png") ? lower.endsWith(".png")
                    : lower.endsWith(".jpg") || lower.endsWith(".jpeg");
            if (!expected) throw error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "IMAGE_TYPE_MISMATCH");
        }
    }

    private void decode(byte[] bytes, String type) {
        try (MemoryCacheImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_IMAGE");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String decodedType = reader.getFormatName();
                if (!(type.equals("image/png") && "png".equalsIgnoreCase(decodedType))
                        && !(type.equals("image/jpeg") && "jpeg".equalsIgnoreCase(decodedType)))
                    throw error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "IMAGE_TYPE_MISMATCH");
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION
                        || (long) width * height > MAX_PIXELS)
                    throw error(HttpStatus.UNPROCESSABLE_ENTITY, "IMAGE_DIMENSIONS_EXCEEDED");
                if (reader.read(0) == null) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_IMAGE");
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException invalid) {
            if (invalid instanceof FanReportApiException api) throw api;
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_IMAGE");
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private FanReportApiException error(HttpStatus status, String code) {
        return new FanReportApiException(status, code);
    }
}
