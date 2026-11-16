package io.github.awsdocmigration.engine;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Consistent read-only view of the latest durable recovery-wave record. */
public record RecoveryWaveSnapshot(
        String jobId,
        String waveId,
        long generation,
        String state,
        String executionStatus,
        String executionArn,
        String stateMachineArn,
        String selectedShardDigest,
        List<Integer> selectedShardNumbers,
        Instant createdAt
) {
    public RecoveryWaveSnapshot {
        requireText(jobId, "jobId");
        requireText(waveId, "waveId");
        requireText(state, "state");
        requireText(executionStatus, "executionStatus");
        selectedShardNumbers = List.copyOf(
                Objects.requireNonNull(selectedShardNumbers, "Selected shards are required")
        );
        if (generation < 1 || selectedShardNumbers.isEmpty()) {
            throw new IllegalArgumentException("Recovery wave generation and shard set must be valid");
        }
        Objects.requireNonNull(createdAt, "Creation time is required");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
