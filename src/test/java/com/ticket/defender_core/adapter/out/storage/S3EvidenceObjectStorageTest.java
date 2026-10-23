package com.ticket.defender_core.adapter.out.storage;

import com.ticket.defender_core.application.service.ValidatedImage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class S3EvidenceObjectStorageTest {
    @Test
    void privateMetadataUsesDedicatedReadClientWithoutBroadeningWriter() {
        S3Client writer = mock(S3Client.class);
        S3Client reader = mock(S3Client.class);
        String key = "fan-reports/server-id/server-asset-id";
        when(reader.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .contentLength(17L).contentType("image/png").eTag("opaque-etag").build());
        S3EvidenceObjectStorage storage = new S3EvidenceObjectStorage(writer, reader, "private-evidence");

        var metadata = storage.head(key);

        assertThat(metadata.byteSize()).isEqualTo(17);
        assertThat(metadata.mediaType()).isEqualTo("image/png");
        assertThat(metadata.opaqueMarker()).isEqualTo("opaque-etag");
        verify(reader).headObject(HeadObjectRequest.builder().bucket("private-evidence").key(key).build());
        verifyNoInteractions(writer);
    }

    @Test
    void writesOnlyServerKeyIntoPrivateBucketWithoutOverwriteOrPublicAcl() throws Exception {
        S3Client client = mock(S3Client.class);
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().eTag("synthetic-etag").build());
        S3EvidenceObjectStorage storage = new S3EvidenceObjectStorage(client, "private-evidence");
        byte[] validatedBytes = new byte[]{1, 2, 3};
        String key = "fan-reports/server-id/server-asset-id";

        storage.checkReady();
        assertThat(storage.put(key, new ValidatedImage(validatedBytes, "image/png", 3, "a".repeat(64))))
                .isEqualTo("synthetic-etag");
        storage.delete(key, null);

        verify(client).headBucket(HeadBucketRequest.builder().bucket("private-evidence").build());
        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> body = ArgumentCaptor.forClass(RequestBody.class);
        verify(client).putObject(put.capture(), body.capture());
        assertThat(put.getValue().bucket()).isEqualTo("private-evidence");
        assertThat(put.getValue().key()).isEqualTo(key);
        assertThat(put.getValue().ifNoneMatch()).isEqualTo("*");
        assertThat(put.getValue().acl()).isNull();
        assertThat(body.getValue().contentStreamProvider().newStream().readAllBytes()).isEqualTo(validatedBytes);
        verify(client).deleteObject(DeleteObjectRequest.builder().bucket("private-evidence").key(key).build());
    }
}
