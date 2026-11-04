
package io.github.awsdocmigration.connector.s3;

import io.github.awsdocmigration.connector.ConnectorCapability;
import io.github.awsdocmigration.domain.DocumentRecord;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.ByteArrayInputStream;
import java.util.Iterator;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class S3SourceTest {

    private S3Client s3;
    private S3Source source;

    @BeforeEach
    void setUp() {
        s3 = mock(S3Client.class);
        source = new S3Source(s3, "source-bucket", "documents/");
    }

    private S3Object object(String key, long size) {
        return S3Object.builder()
                .key(key)
                .size(size)
                .build();
    }

    @Test
    void discoversObjectsFromS3() {

        when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(
                        ListObjectsV2Response.builder()
                                .contents(
                                        object("documents/contract.pdf", 120),
                                        object("documents/report.pdf", 250)
                                )
                                .isTruncated(false)
                                .build()
                );

        Iterator<DocumentRecord> records = source.discover();

        assertTrue(records.hasNext());

        DocumentRecord first = records.next();

        assertEquals("documents/contract.pdf", first.documentId());
        assertEquals("contract.pdf", first.fileName());
        assertEquals(120L, first.sizeBytes());
        assertTrue(first.isLatest());

        assertTrue(records.hasNext());

        DocumentRecord second = records.next();

        assertEquals(
                "documents/report.pdf",
                second.relativePath()
        );

        assertFalse(records.hasNext());

        verify(s3, times(1))
                .listObjectsV2(any(ListObjectsV2Request.class));
    }

    @Test
    void discoversObjectsAcrossMultiplePages() {

        ListObjectsV2Response firstPage =
                ListObjectsV2Response.builder()
                        .contents(
                                object("documents/first.pdf", 100)
                        )
                        .isTruncated(true)
                        .nextContinuationToken("page-2")
                        .build();

        ListObjectsV2Response secondPage =
                ListObjectsV2Response.builder()
                        .contents(
                                object("documents/second.pdf", 200)
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

        verify(s3, times(2))
                .listObjectsV2(any(ListObjectsV2Request.class));
    }

    @Test
    void ignoresEmptyFolderMarkerObjects() {

        when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(
                        ListObjectsV2Response.builder()
                                .contents(
                                        object("documents/folder/", 0),
                                        object("documents/file.pdf", 100)
                                )
                                .isTruncated(false)
                                .build()
                );

        Iterator<DocumentRecord> records = source.discover();

        assertTrue(records.hasNext());

        assertEquals(
                "documents/file.pdf",
                records.next().relativePath()
        );

        assertFalse(records.hasNext());
    }

    @Test
    void rejectsRepeatedContinuationToken() {

        ListObjectsV2Response firstPage =
                ListObjectsV2Response.builder()
                        .contents(
                                object("documents/first.pdf", 100)
                        )
                        .isTruncated(true)
                        .nextContinuationToken("same-token")
                        .build();

        ListObjectsV2Response secondPage =
                ListObjectsV2Response.builder()
                        .contents(
                                object("documents/second.pdf", 100)
                        )
                        .isTruncated(true)
                        .nextContinuationToken("same-token")
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

        assertThrows(
                IllegalStateException.class,
                records::hasNext
        );
    }

    @Test
    void rejectsTruncatedResponseWithoutContinuationToken() {

        when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(
                        ListObjectsV2Response.builder()
                                .contents(
                                        object("documents/file.pdf", 100)
                                )
                                .isTruncated(true)
                                .build()
                );

        Iterator<DocumentRecord> records = source.discover();

        assertThrows(
                IllegalStateException.class,
                records::hasNext
        );
    }

    @Test
    void rejectsObjectOutsideConfiguredPrefix() {

        DocumentRecord record = new DocumentRecord(
                "external/file.pdf",
                "v1",
                "file.pdf",
                "external/file.pdf",
                100,
                null,
                true,
                null
        );

        assertThrows(
                SecurityException.class,
                () -> source.openStream(record)
        );

        verify(s3, never())
                .getObject((GetObjectRequest) any(GetObjectRequest.class));
    }

    @Test
    void rejectsRecordFromDifferentBucket() {

        DocumentRecord record = new DocumentRecord(
                "documents/file.pdf",
                "v1",
                "file.pdf",
                "documents/file.pdf",
                100,
                null,
                true,
                Map.of("sourceBucket", "different-bucket")
        );

        assertThrows(
                SecurityException.class,
                () -> source.openStream(record)
        );

        verify(s3, never())
                .getObject((GetObjectRequest) any(GetObjectRequest.class));
    }

    @Test
    void opensObjectFromConfiguredBucketAndPrefix() throws Exception {

        byte[] content = "sample document".getBytes();

        ResponseInputStream<GetObjectResponse> responseStream =
                new ResponseInputStream<>(
                        GetObjectResponse.builder().build(),
                        AbortableInputStream.create(
                                new ByteArrayInputStream(content)
                        )
                );

        when(s3.getObject(
                (GetObjectRequest) any(GetObjectRequest.class)
        )).thenReturn(responseStream);

        DocumentRecord record = new DocumentRecord(
                "documents/file.pdf",
                "v1",
                "file.pdf",
                "documents/file.pdf",
                content.length,
                null,
                true,
                Map.of("sourceBucket", "source-bucket")
        );

        try (var input = source.openStream(record)) {
            assertArrayEquals(
                    content,
                    input.readAllBytes()
            );
        }

        verify(s3).getObject(
                argThat((GetObjectRequest request) ->
                        request.bucket().equals("source-bucket")
                                && request.key().equals("documents/file.pdf")
                )
        );
    }

    @Test
    void declaresSupportedCapabilities() {

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

        assertTrue(
                source.capabilities().contains(
                        ConnectorCapability.LATEST_VERSION_IDENTIFICATION
                )
        );

        assertFalse(
                source.capabilities().contains(
                        ConnectorCapability.HISTORICAL_VERSION_ENUMERATION
                )
        );

        assertFalse(
                source.capabilities().contains(
                        ConnectorCapability.SOURCE_CHECKSUM
                )
        );
    }
}
