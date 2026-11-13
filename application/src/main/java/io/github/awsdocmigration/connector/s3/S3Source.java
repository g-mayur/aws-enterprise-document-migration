
package io.github.awsdocmigration.connector.s3;

import io.github.awsdocmigration.connector.ConnectorCapability;
import io.github.awsdocmigration.connector.DocumentSource;
import io.github.awsdocmigration.domain.DocumentRecord;
import io.github.awsdocmigration.domain.VersionIdentityType;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * S3 source connector supporting:
 *
 * 1. Versioning-enabled buckets.
 * 2. Versioning-disabled buckets.
 * 3. Versioning-suspended buckets.
 *
 * Source-managed versions are retrieved by their exact S3 VersionId.
 * Snapshot records are retrieved conditionally using their observed ETag
 * and validated against their observed size and last-modified timestamp.
 */
public final class S3Source implements DocumentSource {

    private static final String SOURCE_BUCKET = "sourceBucket";
    private static final String SOURCE_ETAG = "sourceETag";
    private static final String SOURCE_LAST_MODIFIED =
            "sourceLastModified";
    private static final String SOURCE_VERSIONING_STATUS =
            "sourceVersioningStatus";

    private final S3Client s3;
    private final String bucket;
    private final String prefix;

    private volatile BucketVersioningStatus versioningStatus;

    public S3Source(
            S3Client s3,
            String bucket,
            String prefix
    ) {
        if (s3 == null) {
            throw new IllegalArgumentException(
                    "S3 client is required"
            );
        }

        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException(
                    "Source bucket is required"
            );
        }

        this.s3 = s3;
        this.bucket = bucket;
        this.prefix = prefix == null ? "" : prefix;
    }

    @Override
    public String connectorId() {
        return "s3";
    }

    @Override
    public Set<ConnectorCapability> capabilities() {

        Set<ConnectorCapability> capabilities = new HashSet<>();

        capabilities.add(
                ConnectorCapability.DOCUMENT_DISCOVERY
        );

        capabilities.add(
                ConnectorCapability.STREAMING_READ
        );

        capabilities.add(
                ConnectorCapability.LATEST_VERSION_IDENTIFICATION
        );

        BucketVersioningStatus status = versioningStatus;

        if (status == BucketVersioningStatus.ENABLED
                || status == BucketVersioningStatus.SUSPENDED) {

            capabilities.add(
                    ConnectorCapability.HISTORICAL_VERSION_ENUMERATION
            );
        }

        return Set.copyOf(capabilities);
    }

    @Override
    public void validateConnection() {

        s3.headBucket(
                HeadBucketRequest.builder()
                        .bucket(bucket)
                        .build()
        );

        loadVersioningStatus();
    }

    /**
     * Reads the bucket versioning configuration.
     *
     * A missing versioning status means versioning is disabled.
     */
    private BucketVersioningStatus loadVersioningStatus() {

        GetBucketVersioningResponse response =
                s3.getBucketVersioning(
                        GetBucketVersioningRequest.builder()
                                .bucket(bucket)
                                .build()
                );

        BucketVersioningStatus status = response.status();

        if (status == null) {
            status = null;
        } else if (status != BucketVersioningStatus.ENABLED
                && status != BucketVersioningStatus.SUSPENDED) {

            throw new IllegalStateException(
                    "Unsupported S3 bucket versioning status: " + status
            );
        }

        versioningStatus = status;

        return status;
    }

    private BucketVersioningStatus currentVersioningStatus() {

        BucketVersioningStatus status = versioningStatus;

        if (status == null) {
            return loadVersioningStatus();
        }

        return status;
    }

    @Override
    public Iterator<DocumentRecord> discover() {

        BucketVersioningStatus status =
                currentVersioningStatus();

        if (status == BucketVersioningStatus.ENABLED
                || status == BucketVersioningStatus.SUSPENDED) {

            return discoverVersions(status);
        }

        return discoverCurrentObjects();
    }

    /**
     * Discovers current objects in an unversioned bucket.
     */
    private Iterator<DocumentRecord> discoverCurrentObjects() {

        return new Iterator<>() {

            private String continuationToken;

            private Iterator<S3Object> currentPage =
                    List.<S3Object>of().iterator();

            private boolean finished;

            private void loadNextPage() {

                while (!currentPage.hasNext() && !finished) {

                    ListObjectsV2Response response =
                            s3.listObjectsV2(
                                    ListObjectsV2Request.builder()
                                            .bucket(bucket)
                                            .prefix(prefix)
                                            .continuationToken(
                                                    continuationToken
                                            )
                                            .build()
                            );

                    String nextToken =
                            response.nextContinuationToken();

                    if (response.isTruncated()) {

                        if (nextToken == null
                                || nextToken.isBlank()) {

                            throw new IllegalStateException(
                                    "S3 returned a truncated listing "
                                            + "without a continuation token"
                            );
                        }

                        if (nextToken.equals(continuationToken)) {

                            throw new IllegalStateException(
                                    "S3 returned a repeated continuation token"
                            );
                        }
                    }

                    continuationToken = nextToken;
                    finished = !response.isTruncated();

                    currentPage = response.contents()
                            .stream()
                            .filter(S3Source.this::isDocumentObject)
                            .iterator();
                }
            }

            @Override
            public boolean hasNext() {
                loadNextPage();
                return currentPage.hasNext();
            }

            @Override
            public DocumentRecord next() {

                if (!hasNext()) {
                    throw new NoSuchElementException();
                }

                S3Object object = currentPage.next();

                return createSnapshotRecord(
                        object.key(),
                        object.size(),
                        object.eTag(),
                        object.lastModified(),
                        true,
                        BucketVersioningStatus.SUSPENDED == versioningStatus
                                ? BucketVersioningStatus.SUSPENDED.name()
                                : "DISABLED"
                );
            }
        };
    }

    /**
     * Discovers all object versions and excludes delete markers.
     *
     * The API returns object versions and delete markers separately.
     * The isLatest property is supplied by S3 and is preserved.
     */
    private Iterator<DocumentRecord> discoverVersions(
            BucketVersioningStatus status) {

        return new Iterator<>() {

            private String keyMarker;
            private String versionIdMarker;

            private Iterator<ObjectVersion> currentPage =
                    List.<ObjectVersion>of().iterator();

            private boolean finished;

            private final Set<String> seenMarkers = new HashSet<>();

            private void loadNextPage() {

                while (!currentPage.hasNext() && !finished) {

                    ListObjectVersionsResponse response =
                            s3.listObjectVersions(
                                    ListObjectVersionsRequest.builder()
                                            .bucket(bucket)
                                            .prefix(prefix)
                                            .keyMarker(keyMarker)
                                            .versionIdMarker(versionIdMarker)
                                            .build()
                            );

                    if (response.isTruncated()) {

                        String nextKeyMarker =
                                response.nextKeyMarker();

                        String nextVersionIdMarker =
                                response.nextVersionIdMarker();

                        if (nextKeyMarker == null
                                || nextKeyMarker.isBlank()) {

                            throw new IllegalStateException(
                                    "S3 returned a truncated version listing "
                                            + "without a key marker"
                            );
                        }

                        String marker = markerKey(
                                nextKeyMarker,
                                nextVersionIdMarker
                        );

                        if (!seenMarkers.add(marker)) {

                            throw new IllegalStateException(
                                    "S3 returned a repeated version "
                                            + "pagination marker"
                            );
                        }

                        keyMarker = nextKeyMarker;
                        versionIdMarker = nextVersionIdMarker;

                    } else {
                        finished = true;
                    }

                    currentPage = response.versions()
                            .stream()
                            .filter(S3Source.this::isDocumentVersion)
                            .iterator();
                }
            }

            @Override
            public boolean hasNext() {
                loadNextPage();
                return currentPage.hasNext();
            }

            @Override
            public DocumentRecord next() {

                if (!hasNext()) {
                    throw new NoSuchElementException();
                }

                ObjectVersion object = currentPage.next();

                String sourceVersionId = object.versionId();

                if (sourceVersionId == null
                        || sourceVersionId.isBlank()) {

                    throw new IllegalStateException(
                            "S3 returned an object version without a version ID"
                    );
                }

                /*
                 * A null version in a suspended bucket can be replaced
                 * by a subsequent write. It is therefore treated as a
                 * snapshot, not as an immutable historical version.
                 *
                 * In an enabled bucket, an existing null version can be
                 * retrieved using the exact S3 version ID "null".
                 */
                if (status == BucketVersioningStatus.SUSPENDED
                        && "null".equals(sourceVersionId)) {

                    return createSnapshotRecord(
                            object.key(),
                            object.size(),
                            object.eTag(),
                            object.lastModified(),
                            Boolean.TRUE.equals(object.isLatest()),
                            status.name()
                    );
                }

                return new DocumentRecord(
                        object.key(),
                        sourceVersionId,
                        fileName(object.key()),
                        object.key(),
                        object.size(),
                        null,
                        Boolean.TRUE.equals(object.isLatest()),
                        Map.of(
                                SOURCE_BUCKET, bucket,
                                SOURCE_VERSIONING_STATUS, status.name()
                        ),
                        VersionIdentityType.SOURCE_VERSION
                );
            }
        };
    }

    private String markerKey(
            String keyMarker,
            String versionIdMarker) {

        return keyMarker + "\u0000"
                + (versionIdMarker == null ? "" : versionIdMarker);
    }

    private boolean isDocumentObject(S3Object object) {

        String key = object.key();

        if (key == null || key.isBlank()) {
            return false;
        }

        return !(key.endsWith("/") && object.size() == 0);
    }

    private boolean isDocumentVersion(ObjectVersion object) {

        String key = object.key();

        if (key == null || key.isBlank()) {
            return false;
        }

        return !(key.endsWith("/") && object.size() == 0);
    }

    private DocumentRecord createSnapshotRecord(
            String key,
            long size,
            String eTag,
            Instant lastModified,
            boolean isLatest,
            String status) {

        if (eTag == null || eTag.isBlank()) {

            throw new IllegalStateException(
                    "S3 did not return an ETag for snapshot: " + key
            );
        }

        if (lastModified == null) {

            throw new IllegalStateException(
                    "S3 did not return a last-modified timestamp "
                            + "for snapshot: " + key
            );
        }

        String snapshotId = snapshotIdentifier(
                key,
                eTag,
                lastModified,
                size
        );

        return new DocumentRecord(
                key,
                snapshotId,
                fileName(key),
                key,
                size,
                null,
                isLatest,
                Map.of(
                        SOURCE_BUCKET, bucket,
                        SOURCE_ETAG, eTag,
                        SOURCE_LAST_MODIFIED, lastModified.toString(),
                        SOURCE_VERSIONING_STATUS, status
                ),
                VersionIdentityType.SNAPSHOT
        );
    }

    /**
     * Creates a deterministic snapshot identifier from observed attributes.
     *
     * ETag is an object-state validator here, not a SHA-256 checksum.
     */
    private String snapshotIdentifier(
            String key,
            String eTag,
            Instant lastModified,
            long size) {

        String input = key + "\n"
                + eTag + "\n"
                + lastModified + "\n"
                + size;

        try {

            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            byte[] hash = digest.digest(
                    input.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            );

            return "snapshot-" + HexFormat.of().formatHex(hash);

        } catch (NoSuchAlgorithmException e) {

            throw new IllegalStateException(
                    "SHA-256 is unavailable",
                    e
            );
        }
    }

    private String fileName(String key) {

        return key.substring(key.lastIndexOf('/') + 1);
    }

    @Override
    public InputStream openStream(
            DocumentRecord record) throws IOException {

        if (record == null) {

            throw new IllegalArgumentException(
                    "Document record is required"
            );
        }

        String recordBucket =
                record.metadata().get(SOURCE_BUCKET);

        if (recordBucket != null
                && !bucket.equals(recordBucket)) {

            throw new SecurityException(
                    "Document record belongs to a different S3 bucket"
            );
        }

        String key = record.relativePath();

        if (key == null || key.isBlank()) {

            throw new IllegalArgumentException(
                    "S3 object key is required"
            );
        }

        if (!key.startsWith(prefix)) {

            throw new SecurityException(
                    "S3 object key is outside the configured source prefix"
            );
        }

        if (record.versionIdentityType()
                == VersionIdentityType.SOURCE_VERSION) {

            return s3.getObject(
                    GetObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .versionId(record.versionId())
                            .build()
            );
        }

        if (record.versionIdentityType()
                == VersionIdentityType.SNAPSHOT) {

            return openSnapshot(record, key);
        }

        /*
         * Older manifests do not identify an immutable source version
         * or an observed snapshot. Fetching the current object here could
         * silently migrate content different from the manifest's source
         * state, so ambiguous S3 records are rejected.
         */
        throw new IllegalArgumentException(
                "S3 record has LEGACY_UNSPECIFIED version identity; "
                        + "regenerate the manifest with explicit source-version "
                        + "or snapshot identity before migration"
        );
    }

    /**
     * Retrieves a snapshot conditionally and validates its observed state.
     */
    private InputStream openSnapshot(
            DocumentRecord record,
            String key) throws IOException {

        String expectedETag =
                record.metadata().get(SOURCE_ETAG);

        String expectedLastModified =
                record.metadata().get(SOURCE_LAST_MODIFIED);

        if (expectedETag == null || expectedETag.isBlank()) {

            throw new IllegalArgumentException(
                    "Snapshot record is missing its source ETag"
            );
        }

        if (expectedLastModified == null
                || expectedLastModified.isBlank()) {

            throw new IllegalArgumentException(
                    "Snapshot record is missing its last-modified timestamp"
            );
        }

        Instant expectedTimestamp;

        try {

            expectedTimestamp = Instant.parse(expectedLastModified);

        } catch (Exception e) {

            throw new IllegalArgumentException(
                    "Snapshot record has an invalid last-modified timestamp",
                    e
            );
        }

        ResponseInputStream<GetObjectResponse> response =
                s3.getObject(
                        GetObjectRequest.builder()
                                .bucket(bucket)
                                .key(key)
                                .ifMatch(expectedETag)
                                .build()
                );

        boolean valid = false;

        try {

            GetObjectResponse details = response.response();

            if (!expectedETag.equals(details.eTag())) {

                throw new IOException(
                        "S3 snapshot ETag changed for object: " + key
                );
            }

            if (details.lastModified() == null
                    || !expectedTimestamp.equals(details.lastModified())) {

                throw new IOException(
                        "S3 snapshot last-modified timestamp changed "
                                + "for object: " + key
                );
            }

            if (details.contentLength() == null
                    || details.contentLength() != record.sizeBytes()) {

                throw new IOException(
                        "S3 snapshot size changed for object: " + key
                );
            }

            valid = true;

            return response;

        } finally {

            if (!valid) {
                response.close();
            }
        }
    }
}
