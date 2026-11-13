package io.github.awsdocmigration.engine;

/**
 * Fencing token and expiry for one successful shard claim.
 *
 * A worker must retain this lease and use it for renewals and terminal
 * transitions. A reclaimed shard receives a different token, preventing
 * the previous worker from completing work after ownership has changed.
 */
public record ShardLease(
        String jobId,
        int shardNumber,
        String workerId,
        String leaseToken,
        long expiresAtEpochMillis
) {
}
