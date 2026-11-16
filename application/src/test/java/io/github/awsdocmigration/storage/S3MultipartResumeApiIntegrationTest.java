package io.github.awsdocmigration.storage;

import io.github.awsdocmigration.domain.DocumentRecord;
import io.github.awsdocmigration.domain.VersionIdentityType;
import io.github.awsdocmigration.engine.DocumentStateStore;
import io.github.awsdocmigration.engine.MultipartUploadCheckpoint;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.core.sync.ResponseTransformer;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class S3MultipartResumeApiIntegrationTest {

    private static final int PART_SIZE = 5 * 1024 * 1024;

    @TempDir
    Path temporaryDirectory;

    @Test
    void resumesRealS3MultipartUploadAfterWorkerRestart() throws Exception {
        String endpoint = System.getenv("S3_INTEGRATION_ENDPOINT_URL");
        assumeTrue(endpoint != null && !endpoint.isBlank(),
                "Set S3_INTEGRATION_ENDPOINT_URL to run the S3 multipart resume integration test");

        String bucket = "s3-multipart-resume-" + UUID.randomUUID()
                .toString().replace("-", "");
        String key = "integration/resumed-document.bin";
        byte[] content = new byte[PART_SIZE + 37];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i % 251);
        }
        String sha256 = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content));
        Map<String, String> metadata = Map.of(
                "document-id", "resume-document",
                "source-version-id", "resume-version",
                "sha256", sha256);

        try (S3Client s3 = S3ClientFactory.create(Region.US_EAST_1, endpoint)) {
            awaitS3(s3);
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            String uploadId = null;
            boolean completed = false;
            try {
                var created = s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                        .bucket(bucket).key(key)
                        .serverSideEncryption(ServerSideEncryption.AES256)
                        .metadata(metadata).build());
                uploadId = created.uploadId();

                var firstPart = s3.uploadPart(UploadPartRequest.builder()
                                .bucket(bucket).key(key).uploadId(uploadId)
                                .partNumber(1).contentLength((long) PART_SIZE).build(),
                        RequestBody.fromBytes(java.util.Arrays.copyOfRange(
                                content, 0, PART_SIZE)));

                long partSize = PART_SIZE;
                String fingerprint = metadataFingerprint(metadata);
                MultipartUploadCheckpoint persisted = new MultipartUploadCheckpoint(
                        uploadId, bucket, key, (long) content.length, partSize, fingerprint,
                        java.util.List.of(new MultipartUploadCheckpoint.CompletedPart(
                                1, firstPart.eTag())));

                // Simulates a fresh worker process loading the checkpoint persisted by
                // the previous worker. The S3 upload itself is real LocalStack state.
                DocumentStateStore stateStore = mock(DocumentStateStore.class);
                when(stateStore.getMultipartUpload(
                        anyString(), anyInt(), anyString(), anyString(), any()))
                        .thenReturn(Optional.of(persisted));
                when(stateStore.saveMultipartUpload(
                        anyString(), anyInt(), anyString(), anyString(), any(),
                        anyString(), any(), any())).thenReturn(true);
                when(stateStore.clearMultipartUpload(
                        anyString(), anyInt(), anyString(), anyString(), any(),
                        anyString(), any())).thenReturn(true);

                MultipartUploadContext context = new MultipartUploadContext(
                        stateStore, "integration-job", 0, "worker-after-restart",
                        "resume-document", "resume-version",
                        VersionIdentityType.SOURCE_VERSION);
                DocumentRecord record = new DocumentRecord(
                        "resume-document", "resume-version", "document.bin",
                        "source/document.bin", content.length, sha256, true,
                        Map.of(), VersionIdentityType.SOURCE_VERSION);

                S3DestinationWriter writer = new S3DestinationWriter(
                        s3, bucket, PART_SIZE, PART_SIZE, temporaryDirectory);
                writer.write(record, key, new ByteArrayInputStream(content),
                        sha256, context);
                completed = true;

                byte[] downloaded = s3.getObject(
                        GetObjectRequest.builder().bucket(bucket).key(key).build(),
                        ResponseTransformer.toBytes()).asByteArray();
                assertArrayEquals(content, downloaded,
                        "Resumed multipart object must exactly match the source bytes");

                verify(stateStore).clearMultipartUpload(
                        anyString(), anyInt(), anyString(), anyString(), any(),
                        anyString(), any());
            } finally {
                if (!completed && uploadId != null) {
                    try {
                        s3.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                                .bucket(bucket).key(key).uploadId(uploadId).build());
                    } catch (RuntimeException ignored) {
                        // The upload may already have been completed or removed.
                    }
                }
                s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
                s3.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build());
            }
        }
    }

    private static String metadataFingerprint(Map<String, String> metadata)
            throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        new TreeMap<>(metadata).forEach((key, value) -> {
            digest.update(key.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(value.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        });
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void awaitS3(S3Client s3) throws InterruptedException {
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            try {
                s3.listBuckets();
                return;
            } catch (RuntimeException unavailable) {
                lastFailure = unavailable;
                Thread.sleep(1_000);
            }
        }
        fail("S3-compatible service did not become ready within 30 seconds", lastFailure);
    }
}
