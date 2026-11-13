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

    boolean createJob(String jobId, int totalShards, Instant now);

    Optional<MigrationJobState> getJob(String jobId);

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
