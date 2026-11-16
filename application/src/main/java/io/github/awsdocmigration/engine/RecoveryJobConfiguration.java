package io.github.awsdocmigration.engine;

import io.github.awsdocmigration.domain.VersionPolicy;

import java.util.Objects;

/**
 * Immutable execution settings required to dispatch recovery for a prepared job.
 * Values are stored separately from the mutable job lifecycle record.
 */
public record RecoveryJobConfiguration(
        String manifestFingerprint,
        String sourceBucket,
        String sourcePrefix,
        String destinationBucket,
        String manifestS3Uri,
        String documentStateTable,
        String jobStateTable,
        String stateMachineArn,
        VersionPolicy versionPolicy,
        String region,
        int leaseSeconds,
        int maximumDocumentAttempts,
        int maximumConcurrentShards
) {
    public RecoveryJobConfiguration {
        requireSha256(manifestFingerprint);
        requireText(sourceBucket, "sourceBucket");
        Objects.requireNonNull(sourcePrefix, "sourcePrefix is required");
        requireText(destinationBucket, "destinationBucket");
        requireText(manifestS3Uri, "manifestS3Uri");
        requireText(documentStateTable, "documentStateTable");
        requireText(jobStateTable, "jobStateTable");
        requireText(stateMachineArn, "stateMachineArn");
        Objects.requireNonNull(versionPolicy, "versionPolicy is required");
        requireText(region, "region");
        if (leaseSeconds < 3 || maximumDocumentAttempts < 1
                || maximumConcurrentShards < 1) {
            throw new IllegalArgumentException(
                    "Lease seconds must be at least 3; document attempts and concurrency must be positive");
        }
        manifestFingerprint = manifestFingerprint.toLowerCase(java.util.Locale.ROOT);
    }

    private static void requireSha256(String value) {
        if (value == null || !value.matches("[A-Fa-f0-9]{64}")) {
            throw new IllegalArgumentException(
                    "manifestFingerprint must contain exactly 64 hexadecimal characters");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
