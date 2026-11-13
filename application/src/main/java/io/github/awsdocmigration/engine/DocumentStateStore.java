package io.github.awsdocmigration.engine;

import io.github.awsdocmigration.domain.VersionIdentityType;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Persistence contract for coordinating document processing across workers.
 *
 * Implementations must make claims and state transitions conditional and
 * atomic. A false return means the worker did not own a valid lease.
 */
public interface DocumentStateStore {

    /**
     * Backward-compatible entry point for state created before identity types
     * were persisted. It deliberately retains the original legacy key format.
     */
    default boolean claim(
            String jobId, int shardNumber, String documentId, String versionId,
            String workerId, Instant now, Duration leaseDuration, int maximumAttempts
    ) {
        return claim(jobId, shardNumber, documentId, versionId,
                VersionIdentityType.LEGACY_UNSPECIFIED, workerId, now,
                leaseDuration, maximumAttempts);
    }

    boolean claim(
            String jobId, int shardNumber, String documentId, String versionId,
            VersionIdentityType versionIdentityType, String workerId, Instant now,
            Duration leaseDuration, int maximumAttempts
    );

    /**
     * Backward-compatible entry point for legacy document identities.
     */
    default boolean renewLease(
            String jobId, int shardNumber, String documentId, String versionId,
            String workerId, Instant now, Duration leaseDuration
    ) {
        return renewLease(jobId, shardNumber, documentId, versionId,
                VersionIdentityType.LEGACY_UNSPECIFIED, workerId, now, leaseDuration);
    }

    /**
     * Extends a worker's lease only while that worker still owns an
     * unexpired IN_PROGRESS lease.
     *
     * @return true when the lease was extended; false when it was expired,
     *         reclaimed, or owned by another worker
     */
    boolean renewLease(
            String jobId, int shardNumber, String documentId, String versionId,
            VersionIdentityType versionIdentityType, String workerId, Instant now,
            Duration leaseDuration
    );

    /**
     * Backward-compatible entry point for legacy document identities.
     */
    default boolean markCompleted(
            String jobId, int shardNumber, String documentId, String versionId,
            String workerId, String destinationKey, String sha256, Instant now
    ) {
        return markCompleted(jobId, shardNumber, documentId, versionId,
                VersionIdentityType.LEGACY_UNSPECIFIED, workerId,
                destinationKey, sha256, now);
    }

    boolean markCompleted(
            String jobId, int shardNumber, String documentId, String versionId,
            VersionIdentityType versionIdentityType, String workerId,
            String destinationKey, String sha256, Instant now
    );

    /**
     * Backward-compatible entry point for legacy document identities.
     */
    default boolean markFailed(
            String jobId, int shardNumber, String documentId, String versionId,
            String workerId, String errorMessage, Instant now
    ) {
        return markFailed(jobId, shardNumber, documentId, versionId,
                VersionIdentityType.LEGACY_UNSPECIFIED, workerId, errorMessage, now);
    }

    boolean markFailed(
            String jobId, int shardNumber, String documentId, String versionId,
            VersionIdentityType versionIdentityType, String workerId,
            String errorMessage, Instant now
    );

    /**
     * Backward-compatible lookup for legacy document identities.
     */
    default Optional<DocumentMigrationState> get(
            String jobId, int shardNumber, String documentId, String versionId
    ) {
        return get(jobId, shardNumber, documentId, versionId,
                VersionIdentityType.LEGACY_UNSPECIFIED);
    }

    Optional<DocumentMigrationState> get(
            String jobId, int shardNumber, String documentId, String versionId,
            VersionIdentityType versionIdentityType
    );
}
