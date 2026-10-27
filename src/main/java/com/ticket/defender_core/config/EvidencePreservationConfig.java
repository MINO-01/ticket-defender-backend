package com.ticket.defender_core.config;

import com.ticket.defender_core.adapter.out.storage.S3EvidencePreservationStorage;
import com.ticket.defender_core.application.service.EvidencePreservationStorage;
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
public class EvidencePreservationConfig {
    @Bean
    EvidencePreservationStorage evidencePreservationStorage(
            @Value("${ticket-defender.evidence-preservation.enabled:false}") boolean enabled,
            @Value("${ticket-defender.evidence-preservation.storage.profile:UNCONFIGURED}") String profile,
            @Value("${ticket-defender.evidence-preservation.storage.endpoint:}") String endpoint,
            @Value("${ticket-defender.evidence-preservation.storage.region:us-east-1}") String region,
            @Value("${ticket-defender.evidence-preservation.storage.bucket:}") String bucket,
            @Value("${ticket-defender.evidence-preservation.storage.access-key:}") String accessKey,
            @Value("${ticket-defender.evidence-preservation.storage.secret-key:}") String secretKey) {
        if (!enabled) return new DisabledStorage(profile);
        if (profile.isBlank() || "UNCONFIGURED".equals(profile) || endpoint.isBlank() || bucket.isBlank()
                || accessKey.isBlank() || secretKey.isBlank())
            throw new IllegalStateException("Evidence preservation storage configuration is incomplete");
        URI uri = URI.create(endpoint);
        if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme()))
            throw new IllegalStateException("Evidence preservation endpoint must use HTTP or HTTPS");
        if ("http".equals(uri.getScheme()) && !"127.0.0.1".equals(uri.getHost())
                && !"localhost".equalsIgnoreCase(uri.getHost()))
            throw new IllegalStateException("Non-loopback preservation endpoint must use HTTPS");
        S3Client client = S3Client.builder().endpointOverride(uri).region(Region.of(region)).forcePathStyle(true)
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofMinutes(2)).apiCallAttemptTimeout(Duration.ofMinutes(1)).build())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey))).build();
        return new S3EvidencePreservationStorage(client, bucket, profile);
    }

    private record DisabledStorage(String profile) implements EvidencePreservationStorage {
        @Override
        public LockedObject verifyLocked(String objectKey, String versionId, String expectedSha256,
                long expectedSize, long maximumBytes) {
            throw new StorageUnavailableException("Evidence preservation is disabled");
        }
    }
}
