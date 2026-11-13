package io.github.awsdocmigration.engine;

import io.github.awsdocmigration.domain.MigrationShardStatus;

import java.time.Instant;
import java.util.Objects;

/**
 * Read-only classification of persisted shard state for operator inspection.
 * This class never claims, updates, or otherwise mutates a shard.
 */
public final class ShardRecoveryInspector {

    public enum Disposition {
        READY_TO_DISPATCH,
        RETRYABLE_FAILURE,
        WAIT_FOR_ACTIVE_LEASE,
        RECLAIMABLE_EXPIRED_LEASE,
        COMPLETED,
        ATTEMPTS_EXHAUSTED,
        LEASE_EXPIRY_UNKNOWN
    }

    private ShardRecoveryInspector() {
    }

    public static Disposition assess(MigrationShardState shard, Instant now) {
        Objects.requireNonNull(shard, "Shard state is required");
        Objects.requireNonNull(now, "Inspection time is required");

        boolean attemptsExhausted =
                shard.attemptCount() >= JobStateStore.MAX_SHARD_ATTEMPTS;

        return switch (shard.status()) {
            case PENDING -> attemptsExhausted
                    ? Disposition.ATTEMPTS_EXHAUSTED
                    : Disposition.READY_TO_DISPATCH;
            case FAILED -> attemptsExhausted
                    ? Disposition.ATTEMPTS_EXHAUSTED
                    : Disposition.RETRYABLE_FAILURE;
            case COMPLETED -> Disposition.COMPLETED;
            case RUNNING -> {
                if (attemptsExhausted) {
                    yield shard.leaseExpiresAtEpochMillis() == null
                            ? Disposition.LEASE_EXPIRY_UNKNOWN
                            : shard.leaseExpiresAtEpochMillis() <= now.toEpochMilli()
                                    ? Disposition.ATTEMPTS_EXHAUSTED
                                    : Disposition.WAIT_FOR_ACTIVE_LEASE;
                }
                if (shard.leaseExpiresAtEpochMillis() == null) {
                    yield Disposition.LEASE_EXPIRY_UNKNOWN;
                }
                yield shard.leaseExpiresAtEpochMillis() <= now.toEpochMilli()
                        ? Disposition.RECLAIMABLE_EXPIRED_LEASE
                        : Disposition.WAIT_FOR_ACTIVE_LEASE;
            }
        };
    }
}
