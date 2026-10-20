package com.ticket.defender_core.config;

import com.ticket.defender_core.adapter.out.storage.S3EvidenceObjectStorage;
import com.ticket.defender_core.application.service.EvidenceObjectStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;

import java.net.URI;
import java.time.Duration;

@Configuration
@EnableScheduling
public class FanReportStorageConfig {
    @Bean
    EvidenceObjectStorage evidenceObjectStorage(
            @Value("${ticket-defender.fan-report.enabled:false}") boolean enabled,
            @Value("${ticket-defender.fan-report.storage.endpoint:}") String endpoint,
            @Value("${ticket-defender.fan-report.storage.region:us-east-1}") String region,
            @Value("${ticket-defender.fan-report.storage.bucket:}") String bucket,
            @Value("${ticket-defender.fan-report.storage.access-key:}") String accessKey,
            @Value("${ticket-defender.fan-report.storage.secret-key:}") String secretKey) {
        if (!enabled) return new EvidenceObjectStorage() {
            @Override public void checkReady() { throw new IllegalStateException("Public intake is disabled"); }
            @Override public String put(String key, com.ticket.defender_core.application.service.ValidatedImage image) {
                throw new IllegalStateException("Public intake is disabled");
            }
            @Override public void delete(String key, String version) { throw new IllegalStateException("Public intake is disabled"); }
        };
        if (endpoint.isBlank() || bucket.isBlank() || accessKey.isBlank() || secretKey.isBlank())
            throw new IllegalStateException("Private fan report storage configuration is incomplete");
        URI uri = URI.create(endpoint);
        if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme()))
            throw new IllegalStateException("Private fan report storage endpoint must use HTTP or HTTPS");
        if ("http".equals(uri.getScheme()) && !"127.0.0.1".equals(uri.getHost())
                && !"localhost".equalsIgnoreCase(uri.getHost()))
            throw new IllegalStateException("Non-loopback object storage endpoint must use HTTPS");
        S3Client client = S3Client.builder()
                .endpointOverride(uri).region(Region.of(region)).forcePathStyle(true)
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofMinutes(2)).apiCallAttemptTimeout(Duration.ofMinutes(1)).build())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .build();
        return new S3EvidenceObjectStorage(client, bucket);
    }
}
