package io.github.awsdocmigration.storage;

import io.github.awsdocmigration.domain.DocumentRecord;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Writes document content to S3 using a single PUT for small objects and
 * sequential multipart uploads for large objects.
 *
 * Multipart parts are spooled one at a time to temporary disk files so heap
 * usage remains bounded independently of the document size.
 */
public final class S3DestinationWriter implements DestinationWriter {

    private static final long MIB = 1024L * 1024L;
    private static final long GIB = 1024L * MIB;
    private static final long DEFAULT_MULTIPART_THRESHOLD = 64L * MIB;
    private static final long DEFAULT_PART_SIZE = 16L * MIB;
    private static final long MINIMUM_PART_SIZE = 5L * MIB;
    private static final long MAXIMUM_PART_SIZE = 5L * GIB;
    private static final long MAXIMUM_OBJECT_SIZE = 5_000_000_000_000L;
    private static final int MAXIMUM_PARTS = 10_000;
    private static final int COPY_BUFFER_SIZE = 1024 * 1024;

    private final S3Client s3;
    private final String bucket;
    private final long multipartThreshold;
    private final long configuredPartSize;
    private final Path temporaryDirectory;

    public S3DestinationWriter(S3Client s3, String bucket) {
        this(
                s3,
                bucket,
                DEFAULT_MULTIPART_THRESHOLD,
                DEFAULT_PART_SIZE,
                Path.of(System.getProperty("java.io.tmpdir"))
        );
    }

    S3DestinationWriter(
            S3Client s3,
            String bucket,
            long multipartThreshold,
            long configuredPartSize,
            Path temporaryDirectory
    ) {
        this.s3 = Objects.requireNonNull(s3, "S3 client is required");

        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("Destination bucket is required");
        }
        if (multipartThreshold < MINIMUM_PART_SIZE) {
            throw new IllegalArgumentException(
                    "Multipart threshold must be at least 5 MiB"
            );
        }
        if (configuredPartSize < MINIMUM_PART_SIZE
                || configuredPartSize > MAXIMUM_PART_SIZE) {
            throw new IllegalArgumentException(
                    "Multipart part size must be between 5 MiB and 5 GiB"
            );
        }

        this.bucket = bucket;
        this.multipartThreshold = multipartThreshold;
        this.configuredPartSize = configuredPartSize;
        this.temporaryDirectory = Objects.requireNonNull(
                temporaryDirectory,
                "Temporary directory is required"
        );
    }

    @Override
    public void write(
            DocumentRecord record,
            String key,
            InputStream content,
            String sha256
    ) throws Exception {

        Objects.requireNonNull(record, "Document record is required");
        Objects.requireNonNull(content, "Document content stream is required");

        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Destination key is required");
        }
        if (sha256 == null || !sha256.matches("[A-Fa-f0-9]{64}")) {
            throw new IllegalArgumentException(
                    "A valid SHA-256 checksum is required"
            );
        }
        if (record.sizeBytes() > MAXIMUM_OBJECT_SIZE) {
            throw new IllegalArgumentException(
                    "S3 object size exceeds the 5 TB maximum"
            );
        }

        Map<String, String> metadata = Map.of(
                "document-id", safe(record.documentId()),
                "source-version-id", safe(record.versionId()),
                "sha256", sha256.toLowerCase()
        );

        if (record.sizeBytes() <= multipartThreshold) {
            putSingleObject(key, content, record.sizeBytes(), metadata);
            return;
        }

        uploadMultipart(key, content, record.sizeBytes(), metadata);
    }

    private void putSingleObject(
            String key,
            InputStream content,
            long contentLength,
            Map<String, String> metadata
    ) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .serverSideEncryption(ServerSideEncryption.AES256)
                .metadata(metadata)
                .build();

        s3.putObject(
                request,
                RequestBody.fromInputStream(content, contentLength)
        );
    }

    private void uploadMultipart(
            String key,
            InputStream content,
            long contentLength,
            Map<String, String> metadata
    ) throws Exception {

        long partSize = calculatePartSize(contentLength);

        CreateMultipartUploadResponse created = s3.createMultipartUpload(
                CreateMultipartUploadRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .serverSideEncryption(ServerSideEncryption.AES256)
                        .metadata(metadata)
                        .build()
        );

        if (created == null || created.uploadId() == null
                || created.uploadId().isBlank()) {
            throw new IllegalStateException(
                    "S3 did not return a multipart upload ID"
            );
        }

        String uploadId = created.uploadId();
        List<CompletedPart> completedParts = new ArrayList<>();
        long remaining = contentLength;

        try {
            int partNumber = 1;

            while (remaining > 0) {
                long currentPartSize = Math.min(partSize, remaining);
                Path partFile = Files.createTempFile(
                        temporaryDirectory,
                        "migration-part-",
                        ".bin"
                );

                try {
                    copyExactly(content, partFile, currentPartSize);

                    UploadPartResponse uploaded = s3.uploadPart(
                            UploadPartRequest.builder()
                                    .bucket(bucket)
                                    .key(key)
                                    .uploadId(uploadId)
                                    .partNumber(partNumber)
                                    .contentLength(currentPartSize)
                                    .build(),
                            RequestBody.fromFile(partFile)
                    );

                    if (uploaded == null || uploaded.eTag() == null
                            || uploaded.eTag().isBlank()) {
                        throw new IllegalStateException(
                                "S3 did not return an ETag for multipart part "
                                        + partNumber
                        );
                    }

                    completedParts.add(
                            CompletedPart.builder()
                                    .partNumber(partNumber)
                                    .eTag(uploaded.eTag())
                                    .build()
                    );

                } finally {
                    Files.deleteIfExists(partFile);
                }

                remaining -= currentPartSize;
                partNumber++;
            }

            if (content.read() != -1) {
                throw new IOException(
                        "Document stream contains more bytes than declared size "
                                + contentLength
                );
            }

            s3.completeMultipartUpload(
                    CompleteMultipartUploadRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .uploadId(uploadId)
                            .multipartUpload(
                                    CompletedMultipartUpload.builder()
                                            .parts(completedParts)
                                            .build()
                            )
                            .build()
            );

        } catch (Exception failure) {
            try {
                s3.abortMultipartUpload(
                        AbortMultipartUploadRequest.builder()
                                .bucket(bucket)
                                .key(key)
                                .uploadId(uploadId)
                                .build()
                );
            } catch (Exception abortFailure) {
                failure.addSuppressed(abortFailure);
            }

            throw failure;
        }
    }

    private long calculatePartSize(long contentLength) {
        long minimumRequired = (contentLength + MAXIMUM_PARTS - 1)
                / MAXIMUM_PARTS;
        long roundedRequired = ((minimumRequired + MIB - 1) / MIB) * MIB;
        long partSize = Math.max(configuredPartSize, roundedRequired);

        if (partSize > MAXIMUM_PART_SIZE) {
            throw new IllegalArgumentException(
                    "Document requires multipart parts larger than S3's 5 GiB limit"
            );
        }

        return partSize;
    }

    private static void copyExactly(
            InputStream input,
            Path destination,
            long expectedBytes
    ) throws IOException {

        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        long remaining = expectedBytes;

        try (OutputStream output = Files.newOutputStream(destination)) {
            while (remaining > 0) {
                int requested = (int) Math.min(buffer.length, remaining);
                int count = input.read(buffer, 0, requested);

                if (count == -1) {
                    throw new EOFException(
                            "Document stream ended before the expected "
                                    + expectedBytes + " bytes were read"
                    );
                }

                if (count == 0) {
                    int singleByte = input.read();

                    if (singleByte == -1) {
                        throw new EOFException(
                                "Document stream ended before the expected "
                                        + expectedBytes + " bytes were read"
                        );
                    }

                    output.write(singleByte);
                    remaining--;
                } else {
                    output.write(buffer, 0, count);
                    remaining -= count;
                }
            }
        }
    }

    private static String safe(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
