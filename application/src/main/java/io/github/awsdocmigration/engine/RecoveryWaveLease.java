package io.github.awsdocmigration.engine;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Fencing identity for the single active recovery wave associated with a job.
 *
 * The owner token and generation must accompany every subsequent update so a
 * stale coordinator cannot change a newer wave.
 */
public record RecoveryWaveLease(
        String jobId,
        String waveId,
        String ownerToken,
        long generation,
        List<Integer> selectedShardNumbers,
        String selectedShardDigest,
        String executionName,
        Instant createdAt
) {
    public RecoveryWaveLease {
        requireText(jobId, "jobId");
        requireText(waveId, "waveId");
        requireText(ownerToken, "ownerToken");
        requireText(selectedShardDigest, "selectedShardDigest");
        requireText(executionName, "executionName");
        if (generation < 1) {
            throw new IllegalArgumentException("generation must be positive");
        }
        selectedShardNumbers = List.copyOf(
                Objects.requireNonNull(selectedShardNumbers, "Selected shards are required")
        );
        if (selectedShardNumbers.isEmpty()
                || selectedShardNumbers.stream().anyMatch(number -> number == null || number < 0)) {
            throw new IllegalArgumentException("Selected shard numbers must be non-empty and non-negative");
        }
        Objects.requireNonNull(createdAt, "Creation time is required");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
