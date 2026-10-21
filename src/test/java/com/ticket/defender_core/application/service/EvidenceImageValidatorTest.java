package com.ticket.defender_core.application.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvidenceImageValidatorTest {
    private final EvidenceImageValidator validator = new EvidenceImageValidator();

    @Test
    void acceptsActualJpegAndPngAndRecordsDigest() throws Exception {
        MockMultipartFile jpeg = image("jpeg");
        MockMultipartFile png = image("png");
        List<ValidatedImage> images = validator.validate(List.of(jpeg, png));
        assertThat(images).extracting(ValidatedImage::mediaType).containsExactly("image/jpeg", "image/png");
        assertThat(images.get(0).bytes()).isEqualTo(jpeg.getBytes());
        assertThat(images.get(1).bytes()).isEqualTo(png.getBytes());
        assertThat(images.get(0).sha256()).isEqualTo(sha256(jpeg.getBytes()));
        assertThat(images.get(1).sha256()).isEqualTo(sha256(png.getBytes()));
    }

    @Test
    void allowsNoImages() {
        assertThat(validator.validate(List.of())).isEmpty();
    }

    @Test
    void rejectsSpoofedAndBrokenImages() throws Exception {
        byte[] png = image("png").getBytes();
        assertCode(new MockMultipartFile("evidence", "fake.jpg", "image/jpeg", png), "IMAGE_TYPE_MISMATCH");
        byte[] broken = new byte[]{(byte) 137, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3, 4};
        assertCode(new MockMultipartFile("evidence", "broken.png", "image/png", broken), "INVALID_IMAGE");
        byte[] badChecksum = png.clone();
        badChecksum[badChecksum.length - 1] ^= 1;
        assertCode(new MockMultipartFile("evidence", "checksum.png", "image/png", badChecksum), "INVALID_IMAGE");
    }

    @Test
    void rejectsEmptyOversizedTooManyAndHugeDimensions() throws Exception {
        assertCode(new MockMultipartFile("evidence", "empty.png", "image/png", new byte[0]), "EMPTY_FILE");
        assertCode(new MockMultipartFile("evidence", "large.png", "image/png",
                new byte[(int) EvidenceImageValidator.MAX_FILE_BYTES + 1]), "FILE_TOO_LARGE");
        assertThatThrownBy(() -> validator.validate(Collections.nCopies(6, image("png"))))
                .isInstanceOf(FanReportApiException.class).hasMessage("TOO_MANY_FILES");
        BufferedImage huge = new BufferedImage(12_001, 1, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ImageIO.write(huge, "png", buffer);
        assertCode(new MockMultipartFile("evidence", "huge.png", "image/png", buffer.toByteArray()),
                "IMAGE_DIMENSIONS_EXCEEDED");
    }

    private void assertCode(MockMultipartFile file, String code) {
        assertThatThrownBy(() -> validator.validate(List.of(file)))
                .isInstanceOf(FanReportApiException.class).hasMessage(code);
    }

    private MockMultipartFile image(String format) throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, format, bytes);
        String extension = format.equals("jpeg") ? "jpg" : "png";
        return new MockMultipartFile("evidence", "synthetic." + extension, "image/" + format, bytes.toByteArray());
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
