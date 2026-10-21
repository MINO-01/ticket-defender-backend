package com.ticket.defender_core.adapter.out.storage;

import com.ticket.defender_core.application.service.EvidenceObjectStorage;
import com.ticket.defender_core.application.service.ValidatedImage;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

public class S3EvidenceObjectStorage implements EvidenceObjectStorage {
    private final S3Client client;
    private final String bucket;

    public S3EvidenceObjectStorage(S3Client client, String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    @Override
    public void checkReady() {
        client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
    }

    @Override
    public String put(String key, ValidatedImage image) {
        PutObjectResponse response = client.putObject(PutObjectRequest.builder()
                        .bucket(bucket).key(key).contentType(image.mediaType())
                        .ifNoneMatch("*").build(),
                RequestBody.fromBytes(image.bytes()));
        return response.versionId() == null ? response.eTag() : response.versionId();
    }

    @Override
    public void delete(String key, String version) {
        // The dedicated intake bucket is required to have versioning disabled.
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }
}
