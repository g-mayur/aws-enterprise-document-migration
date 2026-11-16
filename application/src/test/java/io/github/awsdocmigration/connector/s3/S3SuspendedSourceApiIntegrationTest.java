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
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.PutBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.VersioningConfiguration;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class S3SuspendedSourceApiIntegrationTest {

    @Test
    void discoversSuspendedNullVersionAsSnapshotAndExcludesDeleteMarkers() throws Exception {
        String endpoint = System.getenv("S3_INTEGRATION_ENDPOINT_URL");
        assumeTrue(endpoint != null && !endpoint.isBlank(),
                "Set S3_INTEGRATION_ENDPOINT_URL to run the suspended-versioning S3 integration test");

        String bucket = "s3-suspended-it-" + UUID.randomUUID().toString().replace("-", "");
        String key = "documents/contracts/agreement.txt";
        String deletedKey = "documents/retired/obsolete.txt";
        byte[] historicalContent = "historical version before suspension".getBytes(StandardCharsets.UTF_8);
        byte[] currentContent = "current null version after suspension".getBytes(StandardCharsets.UTF_8);
        Map<String, DocumentRecord> discovered = new HashMap<>();
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

            String historicalVersionId = s3.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(key).build(),
                    RequestBody.fromBytes(historicalContent)).versionId();
            String deletedObjectVersionId = s3.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(deletedKey).build(),
                    RequestBody.fromString("obsolete content")).versionId();
            assertNotNull(historicalVersionId);
            assertNotNull(deletedObjectVersionId);

            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(deletedKey)
                    .build());

            s3.putBucketVersioning(PutBucketVersioningRequest.builder()
                    .bucket(bucket)
                    .versioningConfiguration(VersioningConfiguration.builder()
                            .status(BucketVersioningStatus.SUSPENDED)
                            .build())
                    .build());

            s3.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(key).build(),
                    RequestBody.fromBytes(currentContent));

            var suspendedListing = s3.listObjectVersions(ListObjectVersionsRequest.builder()
                    .bucket(bucket)
                    .prefix(key)
                    .build());
            ObjectVersion nullVersion = suspendedListing.versions().stream()
                    .filter(version -> key.equals(version.key()))
                    .filter(version -> "null".equals(version.versionId()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "Expected S3 version listing to expose the suspended null version"));
            assertTrue(nullVersion.isLatest(),
                    "The suspended null version should be the latest object version");

            S3Source source = new S3Source(s3, bucket, "documents/");
            source.validateConnection();
            var records = source.discover();
            while (records.hasNext()) {
                DocumentRecord record = records.next();
                discovered.put(record.documentId() + "|" + record.versionId(), record);
            }

            DocumentRecord current = discovered.values().stream()
                    .filter(record -> key.equals(record.documentId())
                            && record.versionIdentityType() == VersionIdentityType.SNAPSHOT)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Expected suspended null version as a snapshot"));
            assertTrue(current.isLatest());
            assertEquals("SUSPENDED", current.metadata().get("sourceVersioningStatus"));
            assertEquals(bucket, current.metadata().get("sourceBucket"));
            assertNotNull(current.metadata().get("sourceETag"));
            assertNotNull(current.metadata().get("sourceLastModified"));

            DocumentRecord historical = discovered.get(key + "|" + historicalVersionId);
            assertNotNull(historical, "The immutable version created before suspension must remain discoverable");
            assertEquals(VersionIdentityType.SOURCE_VERSION, historical.versionIdentityType());
            assertFalse(historical.isLatest());

            try (InputStream input = source.openStream(current)) {
                assertArrayEquals(currentContent, input.readAllBytes());
            }
            try (InputStream input = source.openStream(historical)) {
                assertArrayEquals(historicalContent, input.readAllBytes());
            }

            DocumentRecord retainedDeletedHistory = discovered.get(deletedKey + "|" + deletedObjectVersionId);
            assertNotNull(retainedDeletedHistory,
                    "A delete marker must not erase the separately retained historical object version");
            assertFalse(retainedDeletedHistory.isLatest());
            assertTrue(discovered.values().stream()
                            .noneMatch(record -> deletedKey.equals(record.documentId())
                                    && record.versionId().equals("null")
                                    && record.versionIdentityType() == VersionIdentityType.SOURCE_VERSION),
                    "Delete markers must not be exposed as document records");

            var versionListing = s3.listObjectVersions(ListObjectVersionsRequest.builder()
                    .bucket(bucket)
                    .prefix("documents/")
                    .build());
            DeleteMarkerEntry deleteMarker = versionListing.deleteMarkers().stream()
                    .filter(marker -> deletedKey.equals(marker.key()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Fixture must include a real S3 delete marker"));
            assertTrue(discovered.values().stream()
                            .noneMatch(record -> deletedKey.equals(record.documentId())
                                    && deleteMarker.versionId().equals(record.versionId())),
                    "Delete marker version IDs must not be exposed as document records");
        } finally {
            if (bucketCreated) {
                try (S3Client cleanup = S3ClientFactory.create(Region.US_EAST_1, endpoint)) {
                    var listing = cleanup.listObjectVersions(ListObjectVersionsRequest.builder()
                            .bucket(bucket)
                            .build());
                    for (ObjectVersion version : listing.versions()) {
                        cleanup.deleteObject(DeleteObjectRequest.builder()
                                .bucket(bucket)
                                .key(version.key())
                                .versionId(version.versionId())
                                .build());
                    }
                    for (DeleteMarkerEntry marker : listing.deleteMarkers()) {
                        cleanup.deleteObject(DeleteObjectRequest.builder()
                                .bucket(bucket)
                                .key(marker.key())
                                .versionId(marker.versionId())
                                .build());
                    }
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
