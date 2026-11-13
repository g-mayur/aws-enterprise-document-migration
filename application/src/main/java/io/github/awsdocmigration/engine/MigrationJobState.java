package io.github.awsdocmigration.engine;

import io.github.awsdocmigration.domain.MigrationJobStatus;

/** Persisted read model for a migration job. Timestamps are epoch milliseconds. */
public record MigrationJobState(
        String jobId,
        MigrationJobStatus status,
        int totalShards,
        String lastError,
        long createdAtEpochMillis,
        long updatedAtEpochMillis
) {
}
