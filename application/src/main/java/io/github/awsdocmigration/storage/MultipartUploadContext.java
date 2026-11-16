package io.github.awsdocmigration.storage;

import io.github.awsdocmigration.domain.VersionIdentityType;
import io.github.awsdocmigration.engine.DocumentStateStore;

import java.time.Instant;
import java.util.Objects;

/**
 * Lease-fenced persistence context for resumable S3 multipart uploads.
 */
public record MultipartUploadContext(
        DocumentStateStore stateStore,
        String jobId,
        int shardNumber,
        String workerId,
        String documentId,
        String versionId,
        VersionIdentityType versionIdentityType
) {
    public MultipartUploadContext {
        Objects.requireNonNull(stateStore, "Document state store is required");
        requireText(jobId, "jobId");
        requireText(workerId, "workerId");
        requireText(documentId, "documentId");
        requireText(versionId, "versionId");
        Objects.requireNonNull(versionIdentityType, "Version identity type is required");
        if (shardNumber < 0) {
            throw new IllegalArgumentException("shardNumber cannot be negative");
        }
    }

    public boolean save(
            io.github.awsdocmigration.engine.MultipartUploadCheckpoint checkpoint
    ) {
        return stateStore.saveMultipartUpload(
                jobId, shardNumber, documentId, versionId,
                versionIdentityType, workerId, checkpoint, Instant.now());
    }

    public java.util.Optional<io.github.awsdocmigration.engine.MultipartUploadCheckpoint> load() {
        return stateStore.getMultipartUpload(
                jobId, shardNumber, documentId, versionId, versionIdentityType);
    }

    public boolean clear() {
        return stateStore.clearMultipartUpload(
                jobId, shardNumber, documentId, versionId,
                versionIdentityType, workerId, Instant.now());
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
