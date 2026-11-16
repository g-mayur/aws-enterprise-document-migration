package io.github.awsdocmigration.connector.s3;

import io.github.awsdocmigration.domain.DocumentRecord;
import io.github.awsdocmigration.domain.VersionIdentityType;
import io.github.awsdocmigration.storage.S3ClientFactory;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class S3UnversionedPaginationApiIntegrationTest {

    private static final int OBJECT_COUNT = 1_001;
    private static final String PREFIX = "documents/pagination/";

    @Test
    void discoversObjectsAcrossRealS3ListingPages() throws Exception {
        String endpoint = System.getenv("S3_INTEGRATION_ENDPOINT_URL");
        assumeTrue(endpoint != null && !endpoint.isBlank(),
                "Set S3_INTEGRATION_ENDPOINT_URL to run the S3 pagination integration test");

        String bucket = "s3-pagination-it-" + UUID.randomUUID().toString().replace("-", "");
        String lastKey = PREFIX + "document-1000.txt";
        byte[] lastContent = "pagination boundary object".getBytes(StandardCharsets.UTF_8);
        boolean bucketCreated = false;

        try (S3Client s3 = S3ClientFactory.create(Region.US_EAST_1, endpoint)) {
            awaitS3(s3);
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            bucketCreated = true;

            for (int index = 0; index < OBJECT_COUNT; index++) {
                String key = PREFIX + "document-" + String.format("%04d", index) + ".txt";
                byte[] content = index == OBJECT_COUNT - 1
                        ? lastContent
                        : ("pagination fixture " + index).getBytes(StandardCharsets.UTF_8);
                s3.putObject(
                        PutObjectRequest.builder()
                                .bucket(bucket)
                                .key(key)
                                .contentType("text/plain")
                                .build(),
                        RequestBody.fromBytes(content));
            }

            S3Source source = new S3Source(s3, bucket, "documents/");
            source.validateConnection();

            Set<String> discoveredKeys = new HashSet<>();
            DocumentRecord lastRecord = null;
            var records = source.discover();
            while (records.hasNext()) {
                DocumentRecord record = records.next();
                assertEquals(VersionIdentityType.SNAPSHOT, record.versionIdentityType());
                assertEquals("DISABLED", record.metadata().get("sourceVersioningStatus"));
                assertEquals(bucket, record.metadata().get("sourceBucket"));
                assertNotNull(record.metadata().get("sourceETag"));
                assertNotNull(record.metadata().get("sourceLastModified"));
                assertTrue(record.isLatest());
                assertTrue(discoveredKeys.add(record.relativePath()),
                        "S3 pagination must not return duplicate object keys");
                if (lastKey.equals(record.relativePath())) {
                    lastRecord = record;
                }
            }

            assertEquals(OBJECT_COUNT, discoveredKeys.size(),
                    "The source connector must continue beyond S3's first 1,000-object page");
            assertTrue(discoveredKeys.contains(PREFIX + "document-0000.txt"));
            assertTrue(discoveredKeys.contains(lastKey),
                    "The object on the second S3 listing page must be discovered");
            assertNotNull(lastRecord, "Expected to retain the record from the second listing page");

            try (InputStream input = source.openStream(lastRecord)) {
                assertArrayEquals(lastContent, input.readAllBytes(),
                        "The source connector must read the object discovered on the second page");
            }
        } finally {
            if (bucketCreated) {
                try (S3Client cleanup = S3ClientFactory.create(Region.US_EAST_1, endpoint)) {
                    deleteAllObjects(cleanup, bucket);
                    cleanup.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build());
                }
            }
        }
    }

    private static void deleteAllObjects(S3Client s3, String bucket) {
        String continuationToken = null;
        boolean truncated;
        do {
            var listing = s3.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .continuationToken(continuationToken)
                    .build());
            if (!listing.contents().isEmpty()) {
                var identifiers = listing.contents().stream()
                        .map(object -> ObjectIdentifier.builder().key(object.key()).build())
                        .toList();
                s3.deleteObjects(DeleteObjectsRequest.builder()
                        .bucket(bucket)
                        .delete(delete -> delete.objects(identifiers))
                        .build());
            }
            continuationToken = listing.nextContinuationToken();
            truncated = Boolean.TRUE.equals(listing.isTruncated());
        } while (truncated);
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
