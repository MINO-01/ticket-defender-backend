package com.ticket.defender_core.config;

import com.ticket.defender_core.adapter.out.storage.S3OfficialPdfStorage;
import com.ticket.defender_core.application.service.OfficialPdfStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.time.Duration;

@Configuration
public class OfficialPdfStorageConfig {
    @Bean
    OfficialPdfStorage officialPdfStorage(
            @Value("${ticket-defender.official-pdf.storage.endpoint:}") String endpoint,
            @Value("${ticket-defender.official-pdf.storage.region:us-east-1}") String region,
            @Value("${ticket-defender.official-pdf.storage.bucket:}") String bucket,
            @Value("${ticket-defender.official-pdf.storage.access-key:}") String accessKey,
            @Value("${ticket-defender.official-pdf.storage.secret-key:}") String secretKey,
            @Value("${ticket-defender.official-pdf.operational-mode:DISABLED}") String operationalMode,
            @Value("${ticket-defender.official-pdf.max-bytes:10485760}") long maximumBytes) {
        if (endpoint.isBlank() || bucket.isBlank() || accessKey.isBlank() || secretKey.isBlank())
            return new DisabledOfficialPdfStorage();
        URI uri = URI.create(endpoint);
        if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme()))
            throw new IllegalStateException("Official PDF storage endpoint must use HTTP or HTTPS");
        if ("http".equals(uri.getScheme()) && !"127.0.0.1".equals(uri.getHost())
                && !"localhost".equalsIgnoreCase(uri.getHost()))
            throw new IllegalStateException("Non-loopback official PDF storage endpoint must use HTTPS");
        if ("SYNTHETIC_ISOLATED".equals(operationalMode) && !"127.0.0.1".equals(uri.getHost())
                && !"localhost".equalsIgnoreCase(uri.getHost()))
            throw new IllegalStateException("Synthetic official PDF mode requires loopback private storage");
        S3Client client = S3Client.builder().endpointOverride(uri).region(Region.of(region)).forcePathStyle(true)
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofMinutes(2)).apiCallAttemptTimeout(Duration.ofMinutes(1)).build())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey))).build();
        return new S3OfficialPdfStorage(client, bucket, maximumBytes);
    }

    private static final class DisabledOfficialPdfStorage implements OfficialPdfStorage {
        private static IllegalStateException disabled() {
            return new IllegalStateException("Official PDF private storage is not configured");
        }
        @Override public void checkReady() { throw disabled(); }
        @Override public ObjectProof putIfAbsent(java.util.UUID id, String key, byte[] bytes) { throw disabled(); }
        @Override public ObjectProof verify(java.util.UUID id, String key, String version, String sha, long size) { throw disabled(); }
        @Override public DownloadedPdf download(java.util.UUID id, String key, String version, String sha, long size) { throw disabled(); }
    }
}
