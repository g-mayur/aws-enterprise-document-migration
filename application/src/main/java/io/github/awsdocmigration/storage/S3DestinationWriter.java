package io.github.awsdocmigration.storage;

import io.github.awsdocmigration.domain.DocumentRecord;
import io.github.awsdocmigration.engine.MultipartUploadCheckpoint;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.TreeMap;
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
        write(record, key, content, sha256, null);
    }

    public void write(
            DocumentRecord record,
            String key,
            InputStream content,
            String sha256,
            MultipartUploadContext context
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

        uploadMultipart(key, content, record.sizeBytes(), metadata, context);
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
            String key, InputStream content, long contentLength,
            Map<String, String> metadata, MultipartUploadContext context
    ) throws Exception {
        long partSize = calculatePartSize(contentLength);
        String fingerprint = metadataFingerprint(metadata);
        String uploadId = null;
        Map<Integer, String> remoteEtags = new TreeMap<>();

        if (context != null) {
            Optional<MultipartUploadCheckpoint> saved = context.load();
            if (saved.isPresent()) {
                MultipartUploadCheckpoint candidate = saved.get();
                boolean compatible = candidate.bucket().equals(bucket)
                        && candidate.key().equals(key)
                        && candidate.contentLength() == contentLength
                        && candidate.partSize() == partSize
                        && candidate.metadataFingerprint().equals(fingerprint);
                if (compatible) {
                    uploadId = candidate.uploadId();
                    try {
                        List<Part> remoteParts = listRemoteParts(key, uploadId);
                        if (!areRemotePartsCompatible(remoteParts, contentLength, partSize)) {
                            abortExistingUpload(bucket, key, uploadId);
                            requireCheckpointClear(context);
                            uploadId = null;
                            remoteEtags.clear();
                        } else {
                            for (Part part : remoteParts) {
                                remoteEtags.put(part.partNumber(), part.eTag());
                            }
                        }
                    } catch (S3Exception failure) {
                        String code = failure.awsErrorDetails() == null
                                ? null : failure.awsErrorDetails().errorCode();
                        if (!"NoSuchUpload".equals(code)) throw failure;
                        requireCheckpointClear(context);
                        uploadId = null;
                        remoteEtags.clear();
                    }
                } else {
                    abortExistingUpload(candidate.bucket(), candidate.key(), candidate.uploadId());
                    requireCheckpointClear(context);
                }
            }
        }

        if (uploadId == null) {
            CreateMultipartUploadResponse created = s3.createMultipartUpload(
                    CreateMultipartUploadRequest.builder().bucket(bucket).key(key)
                            .serverSideEncryption(ServerSideEncryption.AES256)
                            .metadata(metadata).build());
            if (created == null || created.uploadId() == null || created.uploadId().isBlank()) {
                throw new IllegalStateException("S3 did not return a multipart upload ID");
            }
            uploadId = created.uploadId();
            if (context != null) {
                try {
                    requireCheckpointSave(context, new MultipartUploadCheckpoint(
                            uploadId, bucket, key, contentLength, partSize, fingerprint, List.of()));
                } catch (Exception checkpointFailure) {
                    try {
                        s3.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                                .bucket(bucket).key(key).uploadId(uploadId).build());
                    } catch (Exception abortFailure) {
                        checkpointFailure.addSuppressed(abortFailure);
                    }
                    throw checkpointFailure;
                }
            }
        }

        try {
            long remaining = contentLength;
            int partNumber = 1;
            while (remaining > 0) {
                long currentPartSize = Math.min(partSize, remaining);
                if (remoteEtags.containsKey(partNumber)) {
                    skipExactly(content, currentPartSize);
                } else {
                    Path partFile = Files.createTempFile(temporaryDirectory, "migration-part-", ".bin");
                    try {
                        copyExactly(content, partFile, currentPartSize);
                        UploadPartResponse uploaded = s3.uploadPart(
                                UploadPartRequest.builder().bucket(bucket).key(key)
                                        .uploadId(uploadId).partNumber(partNumber)
                                        .contentLength(currentPartSize).build(),
                                RequestBody.fromFile(partFile));
                        if (uploaded == null || uploaded.eTag() == null || uploaded.eTag().isBlank()) {
                            throw new IllegalStateException(
                                    "S3 did not return an ETag for multipart part " + partNumber);
                        }
                        remoteEtags.put(partNumber, uploaded.eTag());
                        if (context != null) {
                            List<MultipartUploadCheckpoint.CompletedPart> savedParts =
                                    remoteEtags.entrySet().stream()
                                            .map(e -> new MultipartUploadCheckpoint.CompletedPart(
                                                    e.getKey(), e.getValue()))
                                            .toList();
                            requireCheckpointSave(context, new MultipartUploadCheckpoint(
                                    uploadId, bucket, key, contentLength, partSize,
                                    fingerprint, savedParts));
                        }
                    } finally {
                        Files.deleteIfExists(partFile);
                    }
                }
                remaining -= currentPartSize;
                partNumber++;
            }
            if (content.read() != -1) {
                throw new IOException("Document stream contains more bytes than declared size "
                        + contentLength);
            }
            List<CompletedPart> finalParts = remoteEtags.entrySet().stream()
                    .map(e -> CompletedPart.builder().partNumber(e.getKey())
                            .eTag(e.getValue()).build()).toList();
            s3.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                    .bucket(bucket).key(key).uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(finalParts).build())
                    .build());
            if (context != null) requireCheckpointClear(context);
        } catch (Exception failure) {
            if (context == null) {
                try {
                    s3.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                            .bucket(bucket).key(key).uploadId(uploadId).build());
                } catch (Exception abortFailure) {
                    failure.addSuppressed(abortFailure);
                }
            }
            throw failure;
        }
    }

    private void abortExistingUpload(String uploadBucket, String uploadKey, String uploadId) {
        try {
            s3.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(uploadBucket).key(uploadKey).uploadId(uploadId).build());
        } catch (S3Exception failure) {
            String code = failure.awsErrorDetails() == null
                    ? null : failure.awsErrorDetails().errorCode();
            if (!"NoSuchUpload".equals(code)) throw failure;
        }
    }

    private List<Part> listRemoteParts(String key, String uploadId) {
        List<Part> result = new ArrayList<>();
        Integer marker = null;
        boolean truncated;
        do {
            ListPartsRequest.Builder builder = ListPartsRequest.builder()
                    .bucket(bucket).key(key).uploadId(uploadId);
            if (marker != null) builder.partNumberMarker(marker);
            ListPartsResponse response = s3.listParts(builder.build());
            result.addAll(response.parts());
            truncated = Boolean.TRUE.equals(response.isTruncated());
            Integer nextMarker = response.nextPartNumberMarker();
            if (truncated && (nextMarker == null
                    || nextMarker <= (marker == null ? 0 : marker))) {
                throw new IllegalStateException(
                        "S3 ListParts returned a truncated page without an advancing part-number marker");
            }
            marker = nextMarker;
        } while (truncated);
        return result;
    }

    private static boolean areRemotePartsCompatible(
            List<Part> parts, long contentLength, long partSize) {
        long expectedPartCount = (contentLength + partSize - 1) / partSize;
        Map<Integer, Boolean> seen = new TreeMap<>();
        for (Part part : parts) {
            Integer partNumber = part.partNumber();
            Long partLength = part.size();
            if (partNumber == null || partNumber < 1
                    || partNumber > expectedPartCount
                    || partLength == null
                    || part.eTag() == null || part.eTag().isBlank()
                    || seen.put(partNumber, Boolean.TRUE) != null) {
                return false;
            }
            long expectedLength = Math.min(
                    partSize, contentLength - ((long) partNumber - 1) * partSize);
            if (partLength != expectedLength) {
                return false;
            }
        }
        return true;
    }

    private void requireCheckpointSave(
            MultipartUploadContext context, MultipartUploadCheckpoint checkpoint) {
        if (!context.save(checkpoint)) {
            throw new IllegalStateException(
                    "Multipart checkpoint save rejected because the worker no longer owns its document lease");
        }
    }

    private static void requireCheckpointClear(MultipartUploadContext context) {
        if (!context.clear()) {
            throw new IllegalStateException(
                    "Multipart checkpoint clear rejected because the worker no longer owns its document lease");
        }
    }

    private static void skipExactly(InputStream input, long expectedBytes) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        long remaining = expectedBytes;
        while (remaining > 0) {
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (count < 0) throw new EOFException("Document stream ended before expected part bytes");
            if (count == 0) {
                if (input.read() < 0) throw new EOFException("Document stream ended before expected part bytes");
                remaining--;
            } else remaining -= count;
        }
    }

    private static String metadataFingerprint(Map<String, String> metadata) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            new TreeMap<>(metadata).forEach((key, value) -> {
                digest.update(key.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(value.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
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
