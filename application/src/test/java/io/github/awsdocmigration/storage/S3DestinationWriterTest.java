package io.github.awsdocmigration.storage;

import io.github.awsdocmigration.domain.DocumentRecord;
import io.github.awsdocmigration.domain.VersionIdentityType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class S3DestinationWriterTest {

    private static final String BUCKET = "destination-bucket";
    private static final String KEY = "documents/document-1.pdf";
    private static final String SHA256 = "a".repeat(64);
    private static final int PART_SIZE = 5 * 1024 * 1024;

    @TempDir
    Path temporaryDirectory;

    private S3Client s3;
    private S3DestinationWriter writer;

    @BeforeEach
    void setUp() {
        s3 = mock(S3Client.class);
        writer = new S3DestinationWriter(
                s3,
                BUCKET,
                PART_SIZE,
                PART_SIZE,
                temporaryDirectory
        );
    }

    private DocumentRecord record(long size) {
        return new DocumentRecord(
                "document-1",
                "version-1",
                "document-1.pdf",
                "source/document-1.pdf",
                size,
                null,
                true,
                Map.of(),
                VersionIdentityType.SOURCE_VERSION
        );
    }

    @Test
    void writesSmallObjectUsingSinglePutWithEncryptionAndMetadata()
            throws Exception {

        byte[] content = "small document".getBytes();

        writer.write(
                record(content.length),
                KEY,
                new ByteArrayInputStream(content),
                SHA256
        );

        ArgumentCaptor<PutObjectRequest> requestCaptor =
                ArgumentCaptor.forClass(PutObjectRequest.class);

        verify(s3).putObject(
                requestCaptor.capture(),
                any(RequestBody.class)
        );

        PutObjectRequest request = requestCaptor.getValue();

        assertEquals(BUCKET, request.bucket());
        assertEquals(KEY, request.key());
        assertEquals(ServerSideEncryption.AES256, request.serverSideEncryption());
        assertEquals("document-1", request.metadata().get("document-id"));
        assertEquals("version-1", request.metadata().get("source-version-id"));
        assertEquals(SHA256, request.metadata().get("sha256"));

        verify(s3, never()).createMultipartUpload(
                any(CreateMultipartUploadRequest.class)
        );
    }

    @Test
    void uploadsLargeObjectInMultipartPartsAndCompletesUpload()
            throws Exception {

        byte[] content = new byte[PART_SIZE + 1];

        when(s3.createMultipartUpload(
                any(CreateMultipartUploadRequest.class)
        )).thenReturn(
                CreateMultipartUploadResponse.builder()
                        .uploadId("upload-123")
                        .build()
        );

        when(s3.uploadPart(
                any(UploadPartRequest.class),
                any(RequestBody.class)
        )).thenAnswer(invocation -> {
            UploadPartRequest request = invocation.getArgument(0);
            return UploadPartResponse.builder()
                    .eTag("etag-" + request.partNumber())
                    .build();
        });

        writer.write(
                record(content.length),
                KEY,
                new ByteArrayInputStream(content),
                SHA256
        );

        ArgumentCaptor<UploadPartRequest> partCaptor =
                ArgumentCaptor.forClass(UploadPartRequest.class);

        verify(s3, times(2)).uploadPart(
                partCaptor.capture(),
                any(RequestBody.class)
        );

        assertEquals(1, partCaptor.getAllValues().get(0).partNumber());
        assertEquals(PART_SIZE, partCaptor.getAllValues().get(0).contentLength());

        assertEquals(2, partCaptor.getAllValues().get(1).partNumber());
        assertEquals(1L, partCaptor.getAllValues().get(1).contentLength());

        verify(s3).completeMultipartUpload(any(
                software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest.class
        ));

        verify(s3, never()).abortMultipartUpload(
                any(AbortMultipartUploadRequest.class)
        );

        try (var files = Files.list(temporaryDirectory)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void abortsMultipartUploadWhenPartUploadFails() throws Exception {

        byte[] content = new byte[PART_SIZE + 1];

        when(s3.createMultipartUpload(
                any(CreateMultipartUploadRequest.class)
        )).thenReturn(
                CreateMultipartUploadResponse.builder()
                        .uploadId("upload-failure")
                        .build()
        );

        when(s3.uploadPart(
                any(UploadPartRequest.class),
                any(RequestBody.class)
        )).thenThrow(new IllegalStateException("simulated upload failure"));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> writer.write(
                        record(content.length),
                        KEY,
                        new ByteArrayInputStream(content),
                        SHA256
                )
        );

        assertEquals("simulated upload failure", exception.getMessage());

        verify(s3).abortMultipartUpload(
                any(AbortMultipartUploadRequest.class)
        );

        verify(s3, never()).completeMultipartUpload(any(
                software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest.class
        ));

        try (var files = Files.list(temporaryDirectory)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void abortsMultipartUploadWhenInputEndsBeforeDeclaredSize()
            throws Exception {

        byte[] content = new byte[PART_SIZE];

        when(s3.createMultipartUpload(
                any(CreateMultipartUploadRequest.class)
        )).thenReturn(
                CreateMultipartUploadResponse.builder()
                        .uploadId("upload-short-stream")
                        .build()
        );

        when(s3.uploadPart(
                any(UploadPartRequest.class),
                any(RequestBody.class)
        )).thenReturn(
                UploadPartResponse.builder().eTag("etag-1").build()
        );

        assertThrows(
                EOFException.class,
                () -> writer.write(
                        record(PART_SIZE + 1L),
                        KEY,
                        new ByteArrayInputStream(content),
                        SHA256
                )
        );

        verify(s3).abortMultipartUpload(
                any(AbortMultipartUploadRequest.class)
        );

        verify(s3, never()).completeMultipartUpload(any(
                software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest.class
        ));
    }

    @Test
    void rejectsInvalidChecksumBeforeCallingS3() {

        assertThrows(
                IllegalArgumentException.class,
                () -> writer.write(
                        record(1),
                        KEY,
                        new ByteArrayInputStream(new byte[]{1}),
                        "not-a-sha256"
                )
        );

        verifyNoInteractions(s3);
    }
}
