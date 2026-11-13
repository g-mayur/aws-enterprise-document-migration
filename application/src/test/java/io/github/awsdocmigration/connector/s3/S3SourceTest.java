
package io.github.awsdocmigration.connector.s3;

import io.github.awsdocmigration.connector.ConnectorCapability;
import io.github.awsdocmigration.domain.DocumentRecord;
import io.github.awsdocmigration.domain.VersionIdentityType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class S3SourceTest {

    private static final String BUCKET = "source-bucket";
    private static final String PREFIX = "documents/";

    private static final Instant LAST_MODIFIED =
            Instant.parse("2026-01-10T10:15:30Z");

    private S3Client s3;
    private S3Source source;

    @BeforeEach
    void setUp() {

        s3 = mock(S3Client.class);

        source = new S3Source(
                s3,
                BUCKET,
                PREFIX
        );

        when(s3.getBucketVersioning(
                any(GetBucketVersioningRequest.class)
        )).thenReturn(
                GetBucketVersioningResponse.builder().build()
        );
    }

    private S3Object currentObject(
            String key,
            long size,
            String eTag) {

        return S3Object.builder()
                .key(key)
                .size(size)
                .eTag(eTag)
                .lastModified(LAST_MODIFIED)
                .build();
    }

    private ObjectVersion version(
            String key,
            String versionId,
            long size,
            String eTag,
            boolean latest) {

        return ObjectVersion.builder()
                .key(key)
                .versionId(versionId)
                .size(size)
                .eTag(eTag)
                .lastModified(LAST_MODIFIED)
                .isLatest(latest)
                .build();
    }

    private void configureVersioning(
            BucketVersioningStatus status) {

        when(s3.getBucketVersioning(
                any(GetBucketVersioningRequest.class)
        )).thenReturn(
                GetBucketVersioningResponse.builder()
                        .status(status)
                        .build()
        );
    }

    private ResponseInputStream<GetObjectResponse> response(
            byte[] content,
            String eTag,
            Instant lastModified) {

        return new ResponseInputStream<>(
                GetObjectResponse.builder()
                        .eTag(eTag)
                        .lastModified(lastModified)
                        .contentLength((long) content.length)
                        .build(),
                AbortableInputStream.create(
                        new ByteArrayInputStream(content)
                )
        );
    }

    @Test
    void discoversCurrentObjectsInUnversionedBucket() {

        when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(
                        ListObjectsV2Response.builder()
                                .contents(
                                        currentObject(
                                                "documents/contract.pdf",
                                                120,
                                                "\"etag-1\""
                                        ),
                                        currentObject(
                                                "documents/report.pdf",
                                                250,
                                                "\"etag-2\""
                                        )
                                )
                                .isTruncated(false)
                                .build()
                );

        Iterator<DocumentRecord> records = source.discover();

        assertTrue(records.hasNext());

        DocumentRecord first = records.next();

        assertEquals(
                "documents/contract.pdf",
                first.documentId()
        );

        assertEquals(
                VersionIdentityType.SNAPSHOT,
                first.versionIdentityType()
        );

        assertTrue(first.versionId().startsWith("snapshot-"));

        assertEquals(120L, first.sizeBytes());
        assertTrue(first.isLatest());

        assertEquals(
                "\"etag-1\"",
                first.metadata().get("sourceETag")
        );

        assertTrue(records.hasNext());

        DocumentRecord second = records.next();

        assertEquals(
                "documents/report.pdf",
                second.relativePath()
        );

        assertFalse(records.hasNext());

        verify(s3).listObjectsV2(
                any(ListObjectsV2Request.class)
        );
    }

    @Test
    void discoversObjectsAcrossMultipleUnversionedPages() {

        ListObjectsV2Response firstPage =
                ListObjectsV2Response.builder()
                        .contents(
                                currentObject(
                                        "documents/first.pdf",
                                        100,
                                        "\"etag-1\""
                                )
                        )
                        .isTruncated(true)
                        .nextContinuationToken("page-2")
                        .build();

        ListObjectsV2Response secondPage =
                ListObjectsV2Response.builder()
                        .contents(
                                currentObject(
                                        "documents/second.pdf",
                                        200,
                                        "\"etag-2\""
                                )
                        )
                        .isTruncated(false)
                        .build();

        when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(firstPage, secondPage);

        Iterator<DocumentRecord> records = source.discover();

        assertEquals(
                "documents/first.pdf",
                records.next().relativePath()
        );

        assertEquals(
                "documents/second.pdf",
                records.next().relativePath()
        );

        assertFalse(records.hasNext());

        verify(s3, times(2)).listObjectsV2(
                any(ListObjectsV2Request.class)
        );
    }

    @Test
    void ignoresEmptyFolderMarkerObjects() {

        when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(
                        ListObjectsV2Response.builder()
                                .contents(
                                        currentObject(
                                                "documents/folder/",
                                                0,
                                                "\"folder\""
                                        ),
                                        currentObject(
                                                "documents/file.pdf",
                                                100,
                                                "\"file\""
                                        )
                                )
                                .isTruncated(false)
                                .build()
                );

        Iterator<DocumentRecord> records = source.discover();

        assertEquals(
                "documents/file.pdf",
                records.next().relativePath()
        );

        assertFalse(records.hasNext());
    }

    @Test
    void discoversActualVersionsInVersionedBucket() {

        configureVersioning(BucketVersioningStatus.ENABLED);

        when(s3.listObjectVersions(
                any(ListObjectVersionsRequest.class)
        )).thenReturn(
                ListObjectVersionsResponse.builder()
                        .versions(
                                version(
                                        "documents/contract.pdf",
                                        "version-2",
                                        200,
                                        "\"etag-2\"",
                                        true
                                ),
                                version(
                                        "documents/contract.pdf",
                                        "version-1",
                                        100,
                                        "\"etag-1\"",
                                        false
                                )
                        )
                        .deleteMarkers(
                                DeleteMarkerEntry.builder()
                                        .key("documents/deleted.pdf")
                                        .versionId("delete-version")
                                        .isLatest(true)
                                        .build()
                        )
                        .isTruncated(false)
                        .build()
        );

        Iterator<DocumentRecord> records = source.discover();

        DocumentRecord latest = records.next();

        assertEquals("version-2", latest.versionId());

        assertEquals(
                VersionIdentityType.SOURCE_VERSION,
                latest.versionIdentityType()
        );

        assertTrue(latest.isLatest());

        DocumentRecord previous = records.next();

        assertEquals("version-1", previous.versionId());
        assertFalse(previous.isLatest());

        assertFalse(records.hasNext());
    }

    @Test
    void retrievesExactSourceVersion() throws Exception {

        configureVersioning(BucketVersioningStatus.ENABLED);

        byte[] content = "version content".getBytes();

        ResponseInputStream<GetObjectResponse> response =
                new ResponseInputStream<>(
                        GetObjectResponse.builder().build(),
                        AbortableInputStream.create(
                                new ByteArrayInputStream(content)
                        )
                );

        when(s3.getObject(
                (GetObjectRequest) any(GetObjectRequest.class)
        )).thenReturn(response);

        DocumentRecord record = new DocumentRecord(
                "documents/file.pdf",
                "version-123",
                "file.pdf",
                "documents/file.pdf",
                content.length,
                null,
                true,
                Map.of("sourceBucket", BUCKET),
                VersionIdentityType.SOURCE_VERSION
        );

        try (var input = source.openStream(record)) {

            assertArrayEquals(
                    content,
                    input.readAllBytes()
            );
        }

        verify(s3).getObject(
                argThat((GetObjectRequest request) ->
                        BUCKET.equals(request.bucket())
                                && "documents/file.pdf".equals(request.key())
                                && "version-123".equals(request.versionId())
                )
        );
    }

    @Test
    void discoversNullVersionAsSnapshotWhenVersioningIsSuspended() {

        configureVersioning(BucketVersioningStatus.SUSPENDED);

        when(s3.listObjectVersions(
                any(ListObjectVersionsRequest.class)
        )).thenReturn(
                ListObjectVersionsResponse.builder()
                        .versions(
                                version(
                                        "documents/file.pdf",
                                        "null",
                                        100,
                                        "\"null-etag\"",
                                        true
                                ),
                                version(
                                        "documents/file.pdf",
                                        "historical-1",
                                        80,
                                        "\"old-etag\"",
                                        false
                                )
                        )
                        .isTruncated(false)
                        .build()
        );

        Iterator<DocumentRecord> records = source.discover();

        DocumentRecord current = records.next();

        assertEquals(
                VersionIdentityType.SNAPSHOT,
                current.versionIdentityType()
        );

        assertTrue(current.versionId().startsWith("snapshot-"));

        DocumentRecord historical = records.next();

        assertEquals(
                VersionIdentityType.SOURCE_VERSION,
                historical.versionIdentityType()
        );

        assertEquals(
                "historical-1",
                historical.versionId()
        );

        assertFalse(records.hasNext());
    }

    @Test
    void retrievesSnapshotUsingConditionalEtagRequest() throws Exception {

        byte[] content = "snapshot content".getBytes();

        ResponseInputStream<GetObjectResponse> response =
                response(
                        content,
                        "\"snapshot-etag\"",
                        LAST_MODIFIED
                );

        when(s3.getObject(
                (GetObjectRequest) any(GetObjectRequest.class)
        )).thenReturn(response);

        DocumentRecord record = new DocumentRecord(
                "documents/file.pdf",
                "snapshot-test",
                "file.pdf",
                "documents/file.pdf",
                content.length,
                null,
                true,
                Map.of(
                        "sourceBucket", BUCKET,
                        "sourceETag", "\"snapshot-etag\"",
                        "sourceLastModified", LAST_MODIFIED.toString()
                ),
                VersionIdentityType.SNAPSHOT
        );

        try (var input = source.openStream(record)) {

            assertArrayEquals(
                    content,
                    input.readAllBytes()
            );
        }

        verify(s3).getObject(
                argThat((GetObjectRequest request) ->
                        BUCKET.equals(request.bucket())
                                && "documents/file.pdf".equals(request.key())
                                && "\"snapshot-etag\"".equals(request.ifMatch())
                )
        );
    }

    @Test
    void rejectsSnapshotWhenEtagDoesNotMatch() {

        ResponseInputStream<GetObjectResponse> response =
                response(
                        "changed content".getBytes(),
                        "\"different-etag\"",
                        LAST_MODIFIED
                );

        when(s3.getObject(
                (GetObjectRequest) any(GetObjectRequest.class)
        )).thenReturn(response);

        DocumentRecord record = new DocumentRecord(
                "documents/file.pdf",
                "snapshot-test",
                "file.pdf",
                "documents/file.pdf",
                "changed content".length(),
                null,
                true,
                Map.of(
                        "sourceBucket", BUCKET,
                        "sourceETag", "\"expected-etag\"",
                        "sourceLastModified", LAST_MODIFIED.toString()
                ),
                VersionIdentityType.SNAPSHOT
        );

        assertThrows(
                IOException.class,
                () -> source.openStream(record)
        );
    }

    @Test
    void rejectsObjectOutsideConfiguredPrefix() {

        DocumentRecord record = new DocumentRecord(
                "external/file.pdf",
                "version-1",
                "file.pdf",
                "external/file.pdf",
                100,
                null,
                true,
                null,
                VersionIdentityType.SOURCE_VERSION
        );

        assertThrows(
                SecurityException.class,
                () -> source.openStream(record)
        );

        verify(s3, never()).getObject(
                (GetObjectRequest) any(GetObjectRequest.class)
        );
    }

    @Test
    void rejectsRecordFromDifferentBucket() {

        DocumentRecord record = new DocumentRecord(
                "documents/file.pdf",
                "version-1",
                "file.pdf",
                "documents/file.pdf",
                100,
                null,
                true,
                Map.of("sourceBucket", "different-bucket"),
                VersionIdentityType.SOURCE_VERSION
        );

        assertThrows(
                SecurityException.class,
                () -> source.openStream(record)
        );

        verify(s3, never()).getObject(
                (GetObjectRequest) any(GetObjectRequest.class)
        );
    }

    @Test
    void rejectsRepeatedVersionPaginationMarker() {

        ListObjectVersionsResponse firstPage =
                ListObjectVersionsResponse.builder()
                        .versions(
                                version(
                                        "documents/first.pdf",
                                        "version-1",
                                        100,
                                        "\"etag-1\"",
                                        true
                                )
                        )
                        .isTruncated(true)
                        .nextKeyMarker("documents/marker.pdf")
                        .nextVersionIdMarker("marker-version")
                        .build();

        ListObjectVersionsResponse secondPage =
                ListObjectVersionsResponse.builder()
                        .versions(
                                version(
                                        "documents/second.pdf",
                                        "version-2",
                                        100,
                                        "\"etag-2\"",
                                        true
                                )
                        )
                        .isTruncated(true)
                        .nextKeyMarker("documents/marker.pdf")
                        .nextVersionIdMarker("marker-version")
                        .build();

        configureVersioning(BucketVersioningStatus.ENABLED);

        when(s3.listObjectVersions(
                any(ListObjectVersionsRequest.class)
        )).thenReturn(firstPage, secondPage);

        Iterator<DocumentRecord> records = source.discover();

        assertEquals(
                "documents/first.pdf",
                records.next().relativePath()
        );

        assertThrows(
                IllegalStateException.class,
                records::hasNext
        );

        verify(s3, times(2)).listObjectVersions(
                any(ListObjectVersionsRequest.class)
        );
    }

    @Test
    void discoversVersionsAcrossMultiplePages() {

        configureVersioning(BucketVersioningStatus.ENABLED);

        ListObjectVersionsResponse firstPage =
                ListObjectVersionsResponse.builder()
                        .versions(
                                version(
                                        "documents/first.pdf",
                                        "version-1",
                                        100,
                                        "\"etag-1\"",
                                        true
                                )
                        )
                        .isTruncated(true)
                        .nextKeyMarker("documents/first.pdf")
                        .nextVersionIdMarker("version-1")
                        .build();

        ListObjectVersionsResponse secondPage =
                ListObjectVersionsResponse.builder()
                        .versions(
                                version(
                                        "documents/second.pdf",
                                        "version-2",
                                        200,
                                        "\"etag-2\"",
                                        true
                                )
                        )
                        .isTruncated(false)
                        .build();

        when(s3.listObjectVersions(
                any(ListObjectVersionsRequest.class)
        )).thenReturn(firstPage, secondPage);

        Iterator<DocumentRecord> records = source.discover();

        assertEquals(
                "documents/first.pdf",
                records.next().relativePath()
        );

        assertEquals(
                "documents/second.pdf",
                records.next().relativePath()
        );

        assertFalse(records.hasNext());

        verify(s3, times(2)).listObjectVersions(
                any(ListObjectVersionsRequest.class)
        );
    }

    @Test
    void declaresHistoricalCapabilityAfterVersioningDetection() {

        assertFalse(
                source.capabilities().contains(
                        ConnectorCapability.HISTORICAL_VERSION_ENUMERATION
                )
        );

        configureVersioning(BucketVersioningStatus.ENABLED);

        source.discover();

        assertTrue(
                source.capabilities().contains(
                        ConnectorCapability.HISTORICAL_VERSION_ENUMERATION
                )
        );

        assertTrue(
                source.capabilities().contains(
                        ConnectorCapability.DOCUMENT_DISCOVERY
                )
        );

        assertTrue(
                source.capabilities().contains(
                        ConnectorCapability.STREAMING_READ
                )
        );
    }

    @Test
    void rejectsLegacyUnspecifiedIdentity() {

        DocumentRecord legacyRecord = new DocumentRecord(
                "documents/file.pdf",
                "s3-current",
                "file.pdf",
                "documents/file.pdf",
                100,
                null,
                true,
                Map.of("sourceBucket", BUCKET)
        );

        assertEquals(
                VersionIdentityType.LEGACY_UNSPECIFIED,
                legacyRecord.versionIdentityType()
        );

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> source.openStream(legacyRecord)
        );

        assertTrue(
                exception.getMessage().contains("LEGACY_UNSPECIFIED")
        );

        verify(s3, never()).getObject(
                (GetObjectRequest) any(GetObjectRequest.class)
        );
    }
}
