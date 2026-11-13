package io.github.awsdocmigration.engine;

import io.github.awsdocmigration.domain.MigrationShardStatus;

/** Persisted read model for one shard. Timestamps are epoch milliseconds. */
public record MigrationShardState(
        String jobId,
        int shardNumber,
        MigrationShardStatus status,
        String workerId,
        String lastError,
        int attemptCount,
        long createdAtEpochMillis,
        long updatedAtEpochMillis
) {
}