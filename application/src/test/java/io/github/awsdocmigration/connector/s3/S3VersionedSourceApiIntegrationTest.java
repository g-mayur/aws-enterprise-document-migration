package io.github.awsdocmigration.connector.s3;

import io.github.awsdocmigration.domain.DocumentRecord;
import io.github.awsdocmigration.domain.VersionIdentityType;
import io.github.awsdocmigration.storage.S3ClientFactory;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.PutBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.VersioningConfiguration;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class S3VersionedSourceApiIntegrationTest {

    @Test
    void discoversAndReadsExactVersionsFromVersioningEnabledBucket() throws Exception {
        String endpoint = System.getenv("S3_INTEGRATION_ENDPOINT_URL");
        assumeTrue(endpoint != null && !endpoint.isBlank(),
                "Set S3_INTEGRATION_ENDPOINT_URL to run the S3 versioned-source integration test");

        String bucket = "s3-versioned-it-" + UUID.randomUUID().toString().replace("-", "");
        String key = "documents/contracts/agreement.txt";
        byte[] firstContent = "first version of the agreement".getBytes(StandardCharsets.UTF_8);
        byte[] secondContent = "second version of the agreement".getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> expectedContentByVersion = new HashMap<>();
        boolean bucketCreated = false;

        try (S3Client s3 = S3ClientFactory.create(Region.US_EAST_1, endpoint)) {
            awaitS3(s3);
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            bucketCreated = true;

            s3.putBucketVersioning(PutBucketVersioningRequest.builder()
                    .bucket(bucket)
                    .versioningConfiguration(VersioningConfiguration.builder()
                            .status(BucketVersioningStatus.ENABLED)
                            .build())
                    .build());

            String firstVersionId = s3.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(key).build(),
                    RequestBody.fromBytes(firstContent)).versionId();
            String secondVersionId = s3.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(key).build(),
                    RequestBody.fromBytes(secondContent)).versionId();

            assertNotNull(firstVersionId);
            assertNotNull(secondVersionId);
            assertNotEquals(firstVersionId, secondVersionId);
            expectedContentByVersion.put(firstVersionId, firstContent);
            expectedContentByVersion.put(secondVersionId, secondContent);

            S3Source source = new S3Source(s3, bucket, "documents/");
            source.validateConnection();

            Iterator<DocumentRecord> records = source.discover();
            Map<String, DocumentRecord> discoveredByVersion = new HashMap<>();
            while (records.hasNext()) {
                DocumentRecord record = records.next();
                assertEquals(VersionIdentityType.SOURCE_VERSION, record.versionIdentityType());
                assertEquals(key, record.documentId());
                assertEquals(key, record.relativePath());
                assertEquals("agreement.txt", record.fileName());
                assertEquals(bucket, record.metadata().get("sourceBucket"));
                assertEquals("ENABLED", record.metadata().get("sourceVersioningStatus"));
                discoveredByVersion.put(record.versionId(), record);
            }

            assertEquals(expectedContentByVersion.keySet(), discoveredByVersion.keySet(),
                    "Both source versions must be discovered with their exact S3 version IDs");
            assertTrue(discoveredByVersion.get(secondVersionId).isLatest());
            assertFalse(discoveredByVersion.get(firstVersionId).isLatest());

            for (Map.Entry<String, byte[]> expected : expectedContentByVersion.entrySet()) {
                try (InputStream input = source.openStream(discoveredByVersion.get(expected.getKey()))) {
                    assertArrayEquals(expected.getValue(), input.readAllBytes(),
                            "The source connector must retrieve the exact requested version");
                }
            }
        } finally {
            if (bucketCreated) {
                try (S3Client cleanup = S3ClientFactory.create(Region.US_EAST_1, endpoint)) {
                    var versions = cleanup.listObjectVersions(ListObjectVersionsRequest.builder()
                            .bucket(bucket)
                            .build());
                    versions.versions().forEach(version -> cleanup.deleteObject(
                            DeleteObjectRequest.builder()
                                    .bucket(bucket)
                                    .key(version.key())
                                    .versionId(version.versionId())
                                    .build()));
                    cleanup.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build());
                }
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
        fail("S3-compatible service did not become ready within 30 seconds", lastFailure);
    }
}
