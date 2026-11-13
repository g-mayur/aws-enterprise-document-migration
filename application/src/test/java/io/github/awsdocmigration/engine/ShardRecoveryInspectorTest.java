package io.github.awsdocmigration.engine;

import io.github.awsdocmigration.domain.MigrationShardStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShardRecoveryInspectorTest {

    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");

    @Test
    void identifiesPendingShardAsReadyWithoutConsumingAttempt() {
        assertEquals(ShardRecoveryInspector.Disposition.READY_TO_DISPATCH,
                assess(MigrationShardStatus.PENDING, 0, null));
    }

    @Test
    void identifiesFailedShardAsRetryableUntilAttemptLimit() {
        assertEquals(ShardRecoveryInspector.Disposition.RETRYABLE_FAILURE,
                assess(MigrationShardStatus.FAILED, 2, null));
        assertEquals(ShardRecoveryInspector.Disposition.ATTEMPTS_EXHAUSTED,
                assess(MigrationShardStatus.FAILED, 3, null));
    }

    @Test
    void neverRecommendsReplacementWhileRunningLeaseIsActive() {
        assertEquals(ShardRecoveryInspector.Disposition.WAIT_FOR_ACTIVE_LEASE,
                assess(MigrationShardStatus.RUNNING, 1, NOW.plusSeconds(30).toEpochMilli()));
    }

    @Test
    void identifiesExpiredRunningLeaseAsReclaimableWithinAttemptBudget() {
        assertEquals(ShardRecoveryInspector.Disposition.RECLAIMABLE_EXPIRED_LEASE,
                assess(MigrationShardStatus.RUNNING, 2, NOW.minusSeconds(1).toEpochMilli()));
    }

    @Test
    void identifiesExpiredRunningLeaseAtAttemptLimitAsExhausted() {
        assertEquals(ShardRecoveryInspector.Disposition.ATTEMPTS_EXHAUSTED,
                assess(MigrationShardStatus.RUNNING, 3, NOW.minusSeconds(1).toEpochMilli()));
    }

    @Test
    void treatsMissingRunningLeaseExpiryAsUnknownRatherThanSafeToRetry() {
        assertEquals(ShardRecoveryInspector.Disposition.LEASE_EXPIRY_UNKNOWN,
                assess(MigrationShardStatus.RUNNING, 1, null));
    }

    @Test
    void completedShardIsNeverEligibleForDispatch() {
        assertEquals(ShardRecoveryInspector.Disposition.COMPLETED,
                assess(MigrationShardStatus.COMPLETED, 1, null));
    }

    private static ShardRecoveryInspector.Disposition assess(
            MigrationShardStatus status, int attempts, Long leaseExpiry
    ) {
        MigrationShardState shard = new MigrationShardState(
                "job-1", 0, status, "worker-1", null, attempts,
                NOW.minusSeconds(60).toEpochMilli(), NOW.toEpochMilli(), leaseExpiry
        );
        return ShardRecoveryInspector.assess(shard, NOW);
    }
}
