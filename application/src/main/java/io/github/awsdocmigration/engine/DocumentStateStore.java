package io.github.awsdocmigration.engine;

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

    boolean claim(
            String jobId,
            int shardNumber,
            String documentId,
            String versionId,
            String workerId,
            Instant now,
            Duration leaseDuration,
            int maximumAttempts
    );

    boolean markCompleted(
            String jobId,
            int shardNumber,
            String documentId,
            String versionId,
            String workerId,
            String destinationKey,
            String sha256,
            Instant now
    );

    boolean markFailed(
            String jobId,
            int shardNumber,
            String documentId,
            String versionId,
            String workerId,
            String errorMessage,
            Instant now
    );

    Optional<DocumentMigrationState> get(
            String jobId,
            int shardNumber,
            String documentId,
            String versionId
    );
}
