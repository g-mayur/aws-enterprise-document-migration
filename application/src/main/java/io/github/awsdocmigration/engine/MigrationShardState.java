package io.github.awsdocmigration.engine;

import io.github.awsdocmigration.domain.MigrationShardStatus;

/** Persisted read model for one shard. Timestamps are epoch milliseconds. */
public record MigrationShardState(
        String jobId, int shardNumber, MigrationShardStatus status,
        String workerId, String lastError, int attemptCount,
        long createdAtEpochMillis, long updatedAtEpochMillis,
        Long leaseExpiresAtEpochMillis, String reviewedBy, String reviewNote,
        Long reviewedAtEpochMillis
) {
    public MigrationShardState(
            String jobId, int shardNumber, MigrationShardStatus status,
            String workerId, String lastError, int attemptCount,
            long createdAtEpochMillis, long updatedAtEpochMillis,
            Long leaseExpiresAtEpochMillis
    ) {
        this(jobId, shardNumber, status, workerId, lastError, attemptCount,
                createdAtEpochMillis, updatedAtEpochMillis, leaseExpiresAtEpochMillis,
                null, null, null);
    }

    public MigrationShardState(
            String jobId, int shardNumber, MigrationShardStatus status,
            String workerId, String lastError, int attemptCount,
            long createdAtEpochMillis, long updatedAtEpochMillis
    ) {
        this(jobId, shardNumber, status, workerId, lastError, attemptCount,
                createdAtEpochMillis, updatedAtEpochMillis, null, null, null, null);
    }
}
