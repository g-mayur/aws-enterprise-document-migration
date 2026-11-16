package io.github.awsdocmigration.engine;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable audit snapshot written atomically with the recovery-wave
 * RECONCILED transition.
 */
public record RecoveryReconciliationAudit(
        String jobId,
        String waveId,
        long generation,
        Instant reconciledAt,
        String executionStatus,
        int registeredShards,
        int completedShards,
        int retryableShards,
        int activeShards,
        int blockedShards,
        int exhaustedShards,
        String finalizationStatus
) {
    public RecoveryReconciliationAudit {
        requireText(jobId, "jobId");
        requireText(waveId, "waveId");
        requireText(executionStatus, "executionStatus");
        requireText(finalizationStatus, "finalizationStatus");
        Objects.requireNonNull(reconciledAt, "Reconciliation time is required");
        if (generation < 1 || registeredShards < 0 || completedShards < 0
                || retryableShards < 0 || activeShards < 0
                || blockedShards < 0 || exhaustedShards < 0) {
            throw new IllegalArgumentException("Reconciliation audit values cannot be negative");
        }
        int classified = completedShards + retryableShards + activeShards
                + blockedShards + exhaustedShards;
        if (classified != registeredShards) {
            throw new IllegalArgumentException(
                    "Reconciliation disposition counts must equal registered shard count");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
