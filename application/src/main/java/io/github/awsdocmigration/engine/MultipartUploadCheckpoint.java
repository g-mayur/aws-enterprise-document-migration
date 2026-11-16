package io.github.awsdocmigration.engine;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Durable description of an in-progress S3 multipart upload.
 *
 * The checkpoint is bound to one document identity and its destination
 * contract. Completed part ETags are retained so a later worker can continue
 * the same upload without retransmitting acknowledged parts.
 */
public record MultipartUploadCheckpoint(
        String uploadId,
        String bucket,
        String key,
        long contentLength,
        long partSize,
        String metadataFingerprint,
        List<CompletedPart> completedParts
) {
    public record CompletedPart(int partNumber, String eTag) {
        public CompletedPart {
            if (partNumber < 1 || partNumber > 10_000) {
                throw new IllegalArgumentException(
                        "Multipart part number must be between 1 and 10,000");
            }
            requireText(eTag, "eTag");
        }
    }

    public MultipartUploadCheckpoint {
        requireText(uploadId, "uploadId");
        requireText(bucket, "bucket");
        requireText(key, "key");
        requireText(metadataFingerprint, "metadataFingerprint");
        if (!metadataFingerprint.matches("[A-Fa-f0-9]{64}")) {
            throw new IllegalArgumentException(
                    "metadataFingerprint must be a SHA-256 value");
        }
        if (contentLength <= 0) {
            throw new IllegalArgumentException(
                    "Multipart contentLength must be greater than zero");
        }
        if (partSize < 5L * 1024L * 1024L
                || partSize > 5L * 1024L * 1024L * 1024L) {
            throw new IllegalArgumentException(
                    "Multipart partSize must be between 5 MiB and 5 GiB");
        }
        completedParts = List.copyOf(
                Objects.requireNonNull(completedParts, "completedParts"));
        Set<Integer> numbers = new HashSet<>();
        for (CompletedPart part : completedParts) {
            Objects.requireNonNull(part, "completed part");
            if (!numbers.add(part.partNumber())) {
                throw new IllegalArgumentException(
                        "Duplicate multipart part number: " + part.partNumber());
            }
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
