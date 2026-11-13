package io.github.awsdocmigration.engine;

import io.github.awsdocmigration.domain.MigrationJobStatus;
import io.github.awsdocmigration.domain.MigrationShardStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Finalizes a running job only after every registered shard has reached a
 * terminal outcome. Failed shards remain non-terminal while another retry
 * claim is available.
 */
public final class JobFinalizationCoordinator {

    private final JobStateStore stateStore;

    public JobFinalizationCoordinator(JobStateStore stateStore) {
        this.stateStore = Objects.requireNonNull(
                stateStore, "Job state store is required"
        );
    }

    /**
     * Returns the terminal job state when finalization has completed (or was
     * already completed). Returns empty while the job or any shard is not
     * ready for finalization.
     */
    public Optional<MigrationJobState> finalizeIfReady(String jobId, Instant now) {
        requireText(jobId, "jobId");
        Objects.requireNonNull(now, "Finalization time is required");

        MigrationJobState job = stateStore.getJob(jobId).orElseThrow(
                () -> new IllegalArgumentException("Unknown migration job: " + jobId)
        );

        if (job.status().isTerminal()) {
            return Optional.of(job);
        }
        if (job.status() != MigrationJobStatus.RUNNING) {
            return Optional.empty();
        }

        List<MigrationShardState> shards = stateStore.listShards(jobId);
        if (shards.size() != job.totalShards()) {
            return Optional.empty();
        }

        List<MigrationShardState> ordered = new ArrayList<>(shards);
        ordered.sort(Comparator.comparingInt(MigrationShardState::shardNumber));

        List<String> failures = new ArrayList<>();
        for (int index = 0; index < ordered.size(); index++) {
            MigrationShardState shard = ordered.get(index);
            if (shard.shardNumber() != index) {
                return Optional.empty();
            }

            if (shard.status() == MigrationShardStatus.COMPLETED) {
                continue;
            }
            if (shard.status() != MigrationShardStatus.FAILED
                    || shard.attemptCount() < DynamoDbJobStateStore.MAX_SHARD_ATTEMPTS) {
                return Optional.empty();
            }

            String detail = shard.lastError() == null || shard.lastError().isBlank()
                    ? "maximum attempts exhausted"
                    : shard.lastError();
            failures.add("Shard " + shard.shardNumber() + ": " + detail);
        }

        MigrationJobStatus target = failures.isEmpty()
                ? MigrationJobStatus.COMPLETED
                : MigrationJobStatus.COMPLETED_WITH_ERRORS;
        String aggregateError = failures.isEmpty()
                ? null
                : String.join("; ", failures);

        boolean transitioned = stateStore.transitionJob(
                jobId, MigrationJobStatus.RUNNING, target, now, aggregateError
        );

        MigrationJobState latest = stateStore.getJob(jobId).orElseThrow(
                () -> new IllegalStateException("Job disappeared during finalization: " + jobId)
        );
        if (latest.status().isTerminal()) {
            return Optional.of(latest);
        }
        return transitioned ? Optional.of(latest) : Optional.empty();
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
    }
}
