package io.github.awsdocmigration.engine;

import java.util.Objects;

/**
 * Read-only dead-letter report entry for a shard whose retry budget is spent.
 * Review metadata is recorded separately and never resets attempts or leases.
 */
public record DeadLetterShard(
        String jobId,
        int shardNumber,
        int attemptCount,
        String lastError,
        long createdAtEpochMillis,
        long updatedAtEpochMillis,
        String reviewedBy,
        String reviewNote,
        Long reviewedAtEpochMillis
) {
    public DeadLetterShard {
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalArgumentException("jobId is required");
        }
        if (shardNumber < 0) {
            throw new IllegalArgumentException("shardNumber cannot be negative");
        }
        if (attemptCount < JobStateStore.MAX_SHARD_ATTEMPTS) {
            throw new IllegalArgumentException("Dead-letter shard must have exhausted its retry budget");
        }
        if ((reviewedAtEpochMillis == null) != (reviewedBy == null)) {
            throw new IllegalArgumentException("Review timestamp and reviewer must be recorded together");
        }
        if (reviewedBy != null && reviewedBy.isBlank()) {
            throw new IllegalArgumentException("reviewedBy cannot be blank");
        }
        if (reviewNote != null && reviewedBy == null) {
            throw new IllegalArgumentException("A review note requires review metadata");
        }
    }

    public boolean reviewed() {
        return reviewedAtEpochMillis != null;
    }
}
