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
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.PutBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.VersioningConfiguration;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class S3VersionedPaginationApiIntegrationTest {

    private static final int OBJECT_COUNT = 501;
    private static final String PREFIX = "documents/version-pagination/";

    @Test
    void discoversVersionsAcrossRealS3VersionListingPages() throws Exception {
        String endpoint = System.getenv("S3_INTEGRATION_ENDPOINT_URL");
        assumeTrue(endpoint != null && !endpoint.isBlank(),
                "Set S3_INTEGRATION_ENDPOINT_URL to run the versioned S3 pagination integration test");

        String bucket = "s3-version-pages-it-" + UUID.randomUUID().toString().replace("-", "");
        String boundaryKey = PREFIX + "document-0500.txt";
        byte[] olderContent = "older version on second page".getBytes(StandardCharsets.UTF_8);
        byte[] latestContent = "latest version on second page".getBytes(StandardCharsets.UTF_8);
        Map<String, String> expectedContentByVersion = new HashMap<>();
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

            for (int index = 0; index < OBJECT_COUNT; index++) {
                String key = PREFIX + "document-" + String.format("%04d", index) + ".txt";
                byte[] older = ("older-" + index).getBytes(StandardCharsets.UTF_8);
                byte[] latest = ("latest-" + index).getBytes(StandardCharsets.UTF_8);
                String olderVersionId = s3.putObject(
                        PutObjectRequest.builder().bucket(bucket).key(key).build(),
                        RequestBody.fromBytes(older)).versionId();
                String latestVersionId = s3.putObject(
                        PutObjectRequest.builder().bucket(bucket).key(key).build(),
                        RequestBody.fromBytes(latest)).versionId();
                assertNotNull(olderVersionId);
                assertNotNull(latestVersionId);
                assertNotEquals(olderVersionId, latestVersionId);
                if (key.equals(boundaryKey)) {
                    expectedContentByVersion.put(olderVersionId,
                            new String(olderContent, StandardCharsets.UTF_8));
                    expectedContentByVersion.put(latestVersionId,
                            new String(latestContent, StandardCharsets.UTF_8));
                }
            }

            // Overwrite the boundary key with identifiable payloads for exact-version read assertions.
            String boundaryOlderId = s3.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(boundaryKey).build(),
                    RequestBody.fromBytes(olderContent)).versionId();
            String boundaryLatestId = s3.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(boundaryKey).build(),
                    RequestBody.fromBytes(latestContent)).versionId();
            expectedContentByVersion.clear();
            expectedContentByVersion.put(boundaryOlderId,
                    new String(olderContent, StandardCharsets.UTF_8));
            expectedContentByVersion.put(boundaryLatestId,
                    new String(latestContent, StandardCharsets.UTF_8));

            S3Source source = new S3Source(s3, bucket, "documents/");
            source.validateConnection();

            Map<String, DocumentRecord> boundaryRecords = new HashMap<>();
            Map<String, Integer> recordsPerKey = new HashMap<>();
            Map<String, Integer> latestPerKey = new HashMap<>();
            int discoveredCount = 0;
            var records = source.discover();
            while (records.hasNext()) {
                DocumentRecord record = records.next();
                assertEquals(VersionIdentityType.SOURCE_VERSION, record.versionIdentityType());
                assertEquals("ENABLED", record.metadata().get("sourceVersioningStatus"));
                assertEquals(bucket, record.metadata().get("sourceBucket"));
                recordsPerKey.merge(record.documentId(), 1, Integer::sum);
                if (record.isLatest()) {
                    latestPerKey.merge(record.documentId(), 1, Integer::sum);
                }
                if (boundaryKey.equals(record.documentId())) {
                    boundaryRecords.put(record.versionId(), record);
                }
                discoveredCount++;
            }

            assertEquals(OBJECT_COUNT * 2 + 2, discoveredCount,
                    "The source connector must enumerate every version across the 1,000-version page boundary");
            assertEquals(4, recordsPerKey.get(boundaryKey),
                    "The lexicographically last key must have all four versions across the page boundary");
            assertEquals(1, latestPerKey.get(boundaryKey),
                    "Exactly one version of the boundary key must be marked latest");
            assertEquals(expectedContentByVersion.keySet(), boundaryRecords.keySet());

            for (Map.Entry<String, String> expected : expectedContentByVersion.entrySet()) {
                try (InputStream input = source.openStream(boundaryRecords.get(expected.getKey()))) {
                    assertEquals(expected.getValue(),
                            new String(input.readAllBytes(), StandardCharsets.UTF_8),
                            "The source connector must retrieve the exact requested version");
                }
            }
        } finally {
            if (bucketCreated) {
                try (S3Client cleanup = S3ClientFactory.create(Region.US_EAST_1, endpoint)) {
                    deleteAllVersions(cleanup, bucket);
                    cleanup.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build());
                }
            }
        }
    }

    private static void deleteAllVersions(S3Client s3, String bucket) {
        List<ObjectIdentifier> identifiers = new ArrayList<>();
        String keyMarker = null;
        String versionIdMarker = null;
        boolean truncated;
        do {
            var page = s3.listObjectVersions(ListObjectVersionsRequest.builder()
                    .bucket(bucket)
                    .keyMarker(keyMarker)
                    .versionIdMarker(versionIdMarker)
                    .maxKeys(1_000)
                    .build());
            for (ObjectVersion version : page.versions()) {
                identifiers.add(ObjectIdentifier.builder()
                        .key(version.key())
                        .versionId(version.versionId())
                        .build());
            }
            for (DeleteMarkerEntry marker : page.deleteMarkers()) {
                identifiers.add(ObjectIdentifier.builder()
                        .key(marker.key())
                        .versionId(marker.versionId())
                        .build());
            }
            truncated = Boolean.TRUE.equals(page.isTruncated());
            keyMarker = page.nextKeyMarker();
            versionIdMarker = page.nextVersionIdMarker();
        } while (truncated);

        for (int start = 0; start < identifiers.size(); start += 1_000) {
            List<ObjectIdentifier> batch = identifiers.subList(
                    start, Math.min(start + 1_000, identifiers.size()));
            s3.deleteObjects(DeleteObjectsRequest.builder()
                    .bucket(bucket)
                    .delete(delete -> delete.objects(batch))
                    .build());
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
