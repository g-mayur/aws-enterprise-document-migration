package io.github.awsdocmigration.storage;

import io.github.awsdocmigration.domain.DocumentRecord;
import io.github.awsdocmigration.domain.VersionIdentityType;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class S3ApiIntegrationTest {

    @Test
    void writesAndReadsDocumentThroughRealS3Api() throws Exception {
        String endpoint = System.getenv("S3_INTEGRATION_ENDPOINT_URL");
        assumeTrue(endpoint != null && !endpoint.isBlank(),
                "Set S3_INTEGRATION_ENDPOINT_URL to run the S3 API integration test");

        String bucket = "s3-api-it-" + UUID.randomUUID().toString().replace("-", "");
        String key = "integration/document.txt";
        byte[] content = "S3 API integration test payload".getBytes(StandardCharsets.UTF_8);
        String sha256 = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content));

        try (S3Client s3 = S3ClientFactory.create(Region.US_EAST_1, endpoint)) {
            awaitS3(s3);
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());

            try {
                DocumentRecord record = new DocumentRecord(
                        "integration-document",
                        "integration-version",
                        "document.txt",
                        "document.txt",
                        content.length,
                        sha256,
                        true,
                        Map.of(),
                        VersionIdentityType.SOURCE_VERSION);

                S3DestinationWriter writer = new S3DestinationWriter(s3, bucket);
                writer.write(record, key, new ByteArrayInputStream(content), sha256);

                var head = s3.headObject(HeadObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .build());

                assertEquals(content.length, head.contentLength());
                assertEquals(ServerSideEncryption.AES256, head.serverSideEncryption());
                assertEquals("integration-document", head.metadata().get("document-id"));
                assertEquals("integration-version", head.metadata().get("source-version-id"));
                assertEquals(sha256, head.metadata().get("sha256"));

                var downloaded = s3.getObject(
                        GetObjectRequest.builder().bucket(bucket).key(key).build(),
                        ResponseTransformer.toBytes());

                assertArrayEquals(content, downloaded.asByteArray());
            } finally {
                s3.deleteObject(DeleteObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .build());
                s3.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build());
            }
        }
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
        fail("S3-compatible service did not become ready within 30 seconds",
                lastFailure);
    }
}
