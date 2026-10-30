package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.out.storage.S3OfficialPdfStorage;
import com.ticket.defender_core.application.service.OfficialPdfStorage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TD_ISSUE46_MINIO_BUCKET", matches = "td-issue46-ci-official-pdf")
class S3OfficialPdfStorageMinioTest {
    private S3Client app;
    private S3Client admin;
    private S3OfficialPdfStorage storage;
    private String endpoint;
    private String bucket;

    @BeforeAll
    void connect() {
        endpoint = required("TD_ISSUE32_MINIO_ENDPOINT");
        bucket = required("TD_ISSUE46_MINIO_BUCKET");
        app = client(required("TD_ISSUE46_MINIO_ACCESS_KEY"), required("TD_ISSUE46_MINIO_SECRET_KEY"));
        admin = client(required("TD_ISSUE32_MINIO_ADMIN_ACCESS_KEY"), required("TD_ISSUE32_MINIO_ADMIN_SECRET_KEY"));
        storage = new S3OfficialPdfStorage(app, bucket, 1024 * 1024);
    }

    @AfterAll
    void closeOnlyClients() {
        if (app != null) app.close();
        if (admin != null) admin.close();
    }

    @Test
    void storesVersionedPrivatePdfAndSafelyRecoversTheSameDeterministicIssuanceKey() throws Exception {
        UUID issuance = UUID.randomUUID();
        String key = "official-pdf/integration/" + issuance + ".pdf";
        byte[] pdf = "%PDF-1.7\n% synthetic W09 Korean PDF verification\n%%EOF\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

        storage.checkReady();
        OfficialPdfStorage.ObjectProof stored = storage.putIfAbsent(issuance, key, pdf);
        assertThat(stored.objectVersion()).isNotBlank().isNotEqualTo("null");
        assertThat(stored.size()).isEqualTo(pdf.length);
        assertThat(admin.headObject(HeadObjectRequest.builder().bucket(bucket).key(key)
                .versionId(stored.objectVersion()).build()).metadata()).containsEntry("issuance-id", issuance.toString());
        assertThat(storage.verify(issuance, key, stored.objectVersion(), stored.sha256(), stored.size()))
                .isEqualTo(stored);
        assertThat(storage.download(issuance, key, stored.objectVersion(), stored.sha256(), stored.size()).bytes())
                .isEqualTo(pdf);
        assertThat(storage.putIfAbsent(issuance, key, pdf)).isEqualTo(stored);
        byte[] differentPdf = "%PDF-1.7\n% different synthetic W09 PDF\n%%EOF\n"
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        assertThatThrownBy(() -> storage.putIfAbsent(issuance, key, differentPdf))
                .isInstanceOf(S3OfficialPdfStorage.ObjectVerificationException.class);
        assertThatThrownBy(() -> storage.verify(UUID.randomUUID(), key, stored.objectVersion(), stored.sha256(), stored.size()))
                .isInstanceOf(S3OfficialPdfStorage.ObjectVerificationException.class);
        assertThat(admin.getBucketVersioning(GetBucketVersioningRequest.builder().bucket(bucket).build()).status().name())
                .isEqualTo("ENABLED");
    }

    @Test
    void anonymousAndWrongCredentialsCannotReadOrListTheOfficialPdfBucket() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<Void> object = http.send(HttpRequest.newBuilder(URI.create(endpoint + "/" + bucket
                + "/official-pdf/no-such.pdf")).GET().build(), HttpResponse.BodyHandlers.discarding());
        HttpResponse<Void> listing = http.send(HttpRequest.newBuilder(URI.create(endpoint + "/" + bucket
                + "?list-type=2")).GET().build(), HttpResponse.BodyHandlers.discarding());
        assertThat(object.statusCode()).isEqualTo(403);
        assertThat(listing.statusCode()).isEqualTo(403);
        try (S3Client wrong = client("td46wrong", "synthetic-wrong-secret")) {
            assertThatThrownBy(() -> wrong.headBucket(HeadBucketRequest.builder().bucket(bucket).build()))
                    .isInstanceOfSatisfying(S3Exception.class, error -> assertThat(error.statusCode()).isEqualTo(403));
        }
    }

    private S3Client client(String accessKey, String secretKey) {
        return S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.US_EAST_1).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .build();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
