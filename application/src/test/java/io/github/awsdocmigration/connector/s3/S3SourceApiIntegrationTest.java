package io.github.awsdocmigration.connector.s3;

import io.github.awsdocmigration.domain.DocumentRecord;
import io.github.awsdocmigration.domain.VersionIdentityType;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class S3SourceApiIntegrationTest {

    @Test
    void discoversAndReadsUnversionedObjectUsingObservedSnapshot() throws Exception {
        String endpoint = System.getenv("S3_INTEGRATION_ENDPOINT_URL");
        assumeTrue(endpoint != null && !endpoint.isBlank(),
                "Set S3_INTEGRATION_ENDPOINT_URL to run the S3 source API integration test");

        String bucket = "s3-source-it-" + UUID.randomUUID().toString().replace("-", "");
        String key = "documents/contracts/agreement.txt";
        byte[] content = "S3 source API integration payload".getBytes(StandardCharsets.UTF_8);

        try (S3Client s3 = S3ClientFactory.create(Region.US_EAST_1, endpoint)) {
            awaitS3(s3);
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());

            try {
                s3.putObject(
                        PutObjectRequest.builder()
                                .bucket(bucket)
                                .key(key)
                                .contentType("text/plain")
                                .build(),
                        RequestBody.fromBytes(content));

                S3Source source = new S3Source(s3, bucket, "documents/");
                source.validateConnection();

                Iterator<DocumentRecord> records = source.discover();
                assertTrue(records.hasNext(), "Expected the uploaded object to be discovered");

                DocumentRecord record = records.next();
                assertFalse(records.hasNext(), "Only the uploaded document should be discovered");
                assertEquals(key, record.documentId());
                assertEquals(key, record.relativePath());
                assertEquals("agreement.txt", record.fileName());
                assertEquals(content.length, record.sizeBytes());
                assertEquals(VersionIdentityType.SNAPSHOT, record.versionIdentityType());
                assertEquals(bucket, record.metadata().get("sourceBucket"));
                assertNotNull(record.metadata().get("sourceETag"));
                assertNotNull(record.metadata().get("sourceLastModified"));

                try (InputStream input = source.openStream(record)) {
                    assertArrayEquals(content, input.readAllBytes());
                }
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
