package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.out.storage.S3EvidenceObjectStorage;
import com.ticket.defender_core.application.service.ValidatedImage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TD_ISSUE32_MINIO_ENDPOINT", matches = "http://127\\.0\\.0\\.1:19000")
class S3EvidenceObjectStorageMinioTest {
    private final List<String> keys = new ArrayList<>();
    private S3Client app;
    private S3Client admin;
    private S3EvidenceObjectStorage storage;
    private String endpoint;
    private String bucket;
    private String otherBucket;
    private boolean createdOtherBucket;

    @BeforeAll
    void connect() {
        endpoint = required("TD_ISSUE32_MINIO_ENDPOINT");
        bucket = required("TD_ISSUE32_MINIO_BUCKET");
        app = client(required("TD_ISSUE32_MINIO_ACCESS_KEY"), required("TD_ISSUE32_MINIO_SECRET_KEY"));
        admin = client(required("TD_ISSUE32_MINIO_ADMIN_ACCESS_KEY"),
                required("TD_ISSUE32_MINIO_ADMIN_SECRET_KEY"));
        storage = new S3EvidenceObjectStorage(app, bucket);
        otherBucket = bucket + "-other";
        try {
            admin.headBucket(HeadBucketRequest.builder().bucket(otherBucket).build());
            throw new IllegalStateException("The isolation bucket already exists");
        } catch (S3Exception missing) {
            if (missing.statusCode() != 404) throw missing;
            admin.createBucket(CreateBucketRequest.builder().bucket(otherBucket).build());
            createdOtherBucket = true;
        }
    }

    @AfterAll
    void removeOnlyTestObjects() {
        if (admin != null) {
            for (String key : keys) admin.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
            if (createdOtherBucket)
                admin.deleteBucket(DeleteBucketRequest.builder().bucket(otherBucket).build());
            admin.close();
        }
        if (app != null) app.close();
    }

    @Test
    void jpegAndPngBytesMetadataAndHashesMatchRealObjects() throws Exception {
        storage.checkReady();
        for (String format : List.of("jpeg", "png")) {
            byte[] bytes = image(format);
            String mime = format.equals("png") ? "image/png" : "image/jpeg";
            String key = key();
            String version = storage.put(key, validated(bytes, mime));
            var head = admin.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            byte[] actual = admin.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build())
                    .asByteArray();
            assertThat(head.contentType()).isEqualTo(mime);
            assertThat(head.contentLength()).isEqualTo(bytes.length);
            assertThat(sha256(actual)).isEqualTo(sha256(bytes));
            assertThat(actual).isEqualTo(bytes);
            assertThat(version).isEqualTo(head.eTag());
            assertThat(head.versionId()).isNull();
        }
    }

    @Test
    void anonymousAndInvalidCredentialsCannotReadOrListPrivateBucket() throws Exception {
        String key = key();
        storage.put(key, validated(image("png"), "image/png"));
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<Void> object = http.send(HttpRequest.newBuilder(URI.create(endpoint + "/" + bucket
                + "/" + key)).GET().build(), HttpResponse.BodyHandlers.discarding());
        HttpResponse<Void> listing = http.send(HttpRequest.newBuilder(URI.create(endpoint + "/" + bucket
                + "?list-type=2")).GET().build(), HttpResponse.BodyHandlers.discarding());
        assertThat(object.statusCode()).isEqualTo(403);
        assertThat(listing.statusCode()).isEqualTo(403);

        assertThatThrownBy(() -> app.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build()))
                .isInstanceOfSatisfying(S3Exception.class, error -> assertThat(error.statusCode()).isEqualTo(403));
        // MinIO may list buckets accessible to this user; it must never reveal another bucket.
        assertThat(app.listBuckets().buckets()).extracting(Bucket::name).doesNotContain(otherBucket);
        assertThatThrownBy(() -> app.headBucket(HeadBucketRequest.builder().bucket(otherBucket).build()))
                .isInstanceOfSatisfying(S3Exception.class, error ->
                        assertThat(error.statusCode()).isIn(403, 404));
        try (S3Client wrong = client("td32wrong", "synthetic-wrong-secret")) {
            assertThatThrownBy(() -> wrong.headBucket(HeadBucketRequest.builder().bucket(bucket).build()))
                    .isInstanceOfSatisfying(S3Exception.class, error -> assertThat(error.statusCode()).isEqualTo(403));
        }
    }

    @Test
    void headBucketRequiresGrantedAccessAndConditionalPutPreventsOverwrite() throws Exception {
        storage.checkReady();
        String key = key();
        byte[] first = image("png");
        storage.put(key, validated(first, "image/png"));
        assertThatThrownBy(() -> storage.put(key, validated(image("jpeg"), "image/jpeg")))
                .isInstanceOfSatisfying(S3Exception.class, error -> assertThat(error.statusCode()).isEqualTo(412));
        assertThat(admin.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build())
                .asByteArray()).isEqualTo(first);
        assertThat(admin.getBucketVersioning(GetBucketVersioningRequest.builder().bucket(bucket).build()).status())
                .isNotEqualTo(BucketVersioningStatus.ENABLED);

        storage.delete(key, null);
        assertThatThrownBy(() -> admin.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build()))
                .isInstanceOfSatisfying(S3Exception.class, error -> assertThat(error.statusCode()).isEqualTo(404));
    }

    private String key() {
        String key = "fan-reports/integration-" + UUID.randomUUID() + "/" + UUID.randomUUID();
        keys.add(key);
        return key;
    }

    private S3Client client(String accessKey, String secret) {
        return S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.US_EAST_1)
                .forcePathStyle(true).credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secret))).build();
    }

    private static ValidatedImage validated(byte[] bytes, String mime) {
        return new ValidatedImage(bytes, mime, bytes.length, sha256(bytes));
    }

    private static byte[] image(String format) throws Exception {
        BufferedImage picture = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        picture.setRGB(1, 1, 0x00224466);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(picture, format, bytes)) throw new IllegalStateException("Synthetic codec missing");
        return bytes.toByteArray();
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
