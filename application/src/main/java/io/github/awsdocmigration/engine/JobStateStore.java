package io.github.awsdocmigration.engine;

import io.github.awsdocmigration.domain.MigrationJobStatus;
import io.github.awsdocmigration.domain.MigrationShardStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Durable job and shard lifecycle contract.
 *
 * Implementations must use conditional writes so concurrent callers cannot
 * silently overwrite a state transition made by another worker.
 */
public interface JobStateStore {

    int MAX_SHARD_ATTEMPTS = 3;

    boolean createJob(String jobId, int totalShards, Instant now);

    Optional<MigrationJobState> getJob(String jobId);

    /**
     * Binds a SHA-256 manifest fingerprint to a job.
     *
     * The first fingerprint is persisted. Repeating the same fingerprint
     * succeeds; attempting to bind a different fingerprint fails.
     * Returns false when the job does not exist or has a different fingerprint.
     */
    boolean bindManifestFingerprint(String jobId, String fingerprint);

    /**
     * Atomically binds the complete recovery execution configuration to a READY
     * job whose immutable manifest fingerprint matches the configuration.
     * Repeating an identical bind succeeds; a conflicting bind is rejected.
     */
    boolean bindRecoveryConfiguration(
            String jobId,
            RecoveryJobConfiguration configuration
    );

    /** Returns the immutable recovery configuration, when one has been bound. */
    Optional<RecoveryJobConfiguration> getRecoveryConfiguration(String jobId);


    boolean transitionJob(
            String jobId,
            MigrationJobStatus expected,
            MigrationJobStatus target,
            Instant now,
            String errorMessage
    );

    boolean createShard(String jobId, int shardNumber, Instant now);

    Optional<MigrationShardState> getShard(String jobId, int shardNumber);

    List<MigrationShardState> listShards(String jobId);

    /**
     * Lists terminal FAILED shards that have exhausted the retry budget.
     * Results are ordered by shard number and include manual-review metadata.
     */
    List<DeadLetterShard> listDeadLetterShards(String jobId);

    /**
     * Records an operator's manual review without changing shard status,
     * attempt count, lease state, or retry eligibility.
     *
     * @return false if the shard is not exhausted/FAILED or was already reviewed
     */
    boolean markShardReviewed(
            String jobId, int shardNumber, String reviewer, String reviewNote, Instant now
    );

    /**
     * Atomically claims a pending or previously failed shard, or reclaims a
     * running shard whose lease has expired, while fewer than three total
     * claims have been made. Each successful claim increments the persisted
     * attempt count and returns a unique fencing token. An expired lease at
     * the attempt limit is moved to terminal FAILED state.
     */
    Optional<ShardLease> claimShard(
            String jobId,
            int shardNumber,
            String workerId,
            Instant now,
            java.time.Duration leaseDuration
    );

    /**
     * Extends a lease only while its worker and fencing token still own
     * an unexpired RUNNING shard.
     */
    Optional<ShardLease> renewShardLease(
            ShardLease lease,
            Instant now,
            java.time.Duration leaseDuration
    );

    /**
     * Completes or fails a shard only when the supplied lease still owns
     * the unexpired RUNNING shard.
     */
    boolean transitionClaimedShard(
            ShardLease lease,
            MigrationShardStatus target,
            Instant now,
            String errorMessage
    );

    boolean transitionShard(
            String jobId,
            int shardNumber,
            MigrationShardStatus expected,
            MigrationShardStatus target,
            String workerId,
            Instant now,
            String errorMessage
    );
}
