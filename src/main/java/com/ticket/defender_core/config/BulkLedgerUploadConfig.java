package com.ticket.defender_core.config;

import com.ticket.defender_core.adapter.out.storage.S3BulkLedgerUploadStorage;
import com.ticket.defender_core.application.service.BulkLedgerUploadStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/** BL-02 is fail-closed until the isolated upload bucket and credentials are explicitly configured. */
@Configuration
public class BulkLedgerUploadConfig {
    @Bean
    BulkLedgerUploadStorage bulkLedgerUploadStorage(
            @Value("${ticket-defender.bulk-ledger-upload.enabled:false}") boolean enabled,
            @Value("${ticket-defender.bulk-ledger-upload.storage.endpoint:}") String endpoint,
            @Value("${ticket-defender.bulk-ledger-upload.storage.region:us-east-1}") String region,
            @Value("${ticket-defender.bulk-ledger-upload.storage.bucket:}") String bucket,
            @Value("${ticket-defender.bulk-ledger-upload.storage.access-key:}") String key,
            @Value("${ticket-defender.bulk-ledger-upload.storage.secret-key:}") String secret) {
        if (!enabled) return new BulkLedgerUploadStorage() {
            @Override public UploadSession createUploadSession(String objectKey, long bytes, java.time.Instant expiresAt) { throw new Unavailable("Bulk ledger upload is disabled"); }
            @Override public VerifiedObject verify(String objectKey, String versionId, long maximumBytes) { throw new Unavailable("Bulk ledger upload is disabled"); }
            @Override public java.io.InputStream openVersion(String objectKey, String versionId) { throw new Unavailable("Bulk ledger upload is disabled"); }
        };
        if (endpoint.isBlank() || bucket.isBlank() || key.isBlank() || secret.isBlank())
            throw new IllegalStateException("Bulk ledger upload storage configuration is incomplete");
        URI uri = URI.create(endpoint);
        if (!"https".equals(uri.getScheme()) && !("http".equals(uri.getScheme()) && ("localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost()))))
            throw new IllegalStateException("Bulk ledger storage endpoint must use HTTPS outside loopback");
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(key, secret));
        S3Client client = S3Client.builder().endpointOverride(uri).region(Region.of(region)).forcePathStyle(true).credentialsProvider(credentials).build();
        S3Presigner presigner = S3Presigner.builder().endpointOverride(uri).region(Region.of(region)).credentialsProvider(credentials)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build();
        return new S3BulkLedgerUploadStorage(client, presigner, bucket);
    }
}
