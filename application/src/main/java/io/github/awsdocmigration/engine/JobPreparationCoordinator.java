package io.github.awsdocmigration.engine;

import io.github.awsdocmigration.domain.MigrationJobStatus;
import io.github.awsdocmigration.domain.MigrationShardStatus;

import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Creates a migration job's complete shard plan before workers are allowed
 * to execute it.
 *
 * Preparation is resumable: an existing CREATED or DISCOVERING job is
 * continued, and already registered PENDING shards are left unchanged.
 */
public final class JobPreparationCoordinator {

    private final JobStateStore stateStore;

    public JobPreparationCoordinator(JobStateStore stateStore) {
        this.stateStore = Objects.requireNonNull(
                stateStore,
                "Job state store is required"
        );
    }

    /**
     * Creates or resumes a job only when its immutable manifest fingerprint
     * matches the exact manifest artifact supplied by the caller.
     *
     * The fingerprint is bound before shard preparation starts. A job that
     * was previously prepared with a different manifest is rejected.
     */
    public MigrationJobState prepare(
            String jobId,
            int totalShards,
            Instant now,
            String manifestFingerprint
    ) {
        requireText(jobId, "jobId");
        Objects.requireNonNull(now, "Preparation time is required");

        if (totalShards < 0) {
            throw new IllegalArgumentException(
                    "totalShards cannot be negative"
            );
        }

        if (manifestFingerprint == null
                || !manifestFingerprint.matches("[A-Fa-f0-9]{64}")) {
            throw new IllegalArgumentException(
                    "manifestFingerprint must contain exactly 64 hexadecimal characters"
            );
        }

        stateStore.createJob(jobId, totalShards, now);

        MigrationJobState existingJob = stateStore.getJob(jobId)
                .orElseThrow(() -> new IllegalStateException(
                        "Job does not exist after create attempt: " + jobId
                ));

        if (existingJob.totalShards() != totalShards) {
            throw new IllegalStateException(
                    "Existing job has " + existingJob.totalShards()
                            + " shards; requested " + totalShards
            );
        }

        if (!stateStore.bindManifestFingerprint(
                jobId,
                manifestFingerprint
        )) {
            throw new IllegalStateException(
                    "Manifest fingerprint does not match the existing job, "
                            + "or the job no longer accepts an initial fingerprint: "
                            + jobId
            );
        }

        return prepare(jobId, totalShards, now);
    }

    /**
     * Creates or resumes a job and registers shard numbers 0 through
     * totalShards - 1. The job becomes READY only after every shard exists.
     *
     * A job that has already advanced beyond preparation is not modified.
     */
    public MigrationJobState prepare(
            String jobId,
            int totalShards,
            Instant now
    ) {
        requireText(jobId, "jobId");
        Objects.requireNonNull(now, "Preparation time is required");

        if (totalShards < 0) {
            throw new IllegalArgumentException(
                    "totalShards cannot be negative"
            );
        }

        stateStore.createJob(jobId, totalShards, now);
        MigrationJobState job = stateStore.getJob(jobId)
                .orElseThrow(() -> new IllegalStateException(
                        "Job does not exist after create attempt: " + jobId
                ));

        if (job.totalShards() != totalShards) {
            throw new IllegalStateException(
                    "Existing job has " + job.totalShards()
                            + " shards; requested " + totalShards
            );
        }

        if (job.status() == MigrationJobStatus.READY
                || job.status() == MigrationJobStatus.RUNNING
                || job.status().isTerminal()) {
            verifyRegisteredShards(jobId, totalShards);
            return job;
        }

        if (job.status() == MigrationJobStatus.CREATED) {
            boolean transitioned = stateStore.transitionJob(
                    jobId,
                    MigrationJobStatus.CREATED,
                    MigrationJobStatus.DISCOVERING,
                    now,
                    null
            );

            job = stateStore.getJob(jobId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Job disappeared during preparation: " + jobId
                    ));

            if (!transitioned && job.status() == MigrationJobStatus.READY) {
                verifyRegisteredShards(jobId, totalShards);
                return job;
            }
        }

        if (job.status() != MigrationJobStatus.DISCOVERING) {
            throw new IllegalStateException(
                    "Cannot prepare job in state " + job.status()
            );
        }

        for (int shardNumber = 0; shardNumber < totalShards; shardNumber++) {
            registerShard(jobId, shardNumber, now);
        }

        boolean ready = stateStore.transitionJob(
                jobId,
                MigrationJobStatus.DISCOVERING,
                MigrationJobStatus.READY,
                now,
                null
        );

        if (!ready) {
            job = stateStore.getJob(jobId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Job disappeared while becoming READY: " + jobId
                    ));

            if (job.status() != MigrationJobStatus.READY) {
                throw new IllegalStateException(
                        "Unable to mark job READY; current state is "
                                + job.status()
                );
            }
        }

        verifyRegisteredShards(jobId, totalShards);

        return stateStore.getJob(jobId)
                .orElseThrow(() -> new IllegalStateException(
                        "Job disappeared after preparation: " + jobId
                ));
    }

    private void registerShard(
            String jobId,
            int shardNumber,
            Instant now
    ) {
        if (stateStore.createShard(jobId, shardNumber, now)) {
            return;
        }

        MigrationShardState existing = stateStore.getShard(jobId, shardNumber)
                .orElseThrow(() -> new IllegalStateException(
                        "Unable to register shard " + shardNumber
                                + " for job " + jobId
                ));

        if (existing.status() != MigrationShardStatus.PENDING) {
            throw new IllegalStateException(
                    "Shard " + shardNumber + " is already "
                            + existing.status()
                            + " while job preparation is incomplete"
            );
        }
    }

    private void verifyRegisteredShards(String jobId, int totalShards) {
        Set<Integer> registered = new HashSet<>();

        for (MigrationShardState shard : stateStore.listShards(jobId)) {
            registered.add(shard.shardNumber());
        }

        if (registered.size() != totalShards) {
            throw new IllegalStateException(
                    "Job " + jobId + " has " + registered.size()
                            + " registered shards; expected " + totalShards
            );
        }

        for (int shardNumber = 0; shardNumber < totalShards; shardNumber++) {
            if (!registered.contains(shardNumber)) {
                throw new IllegalStateException(
                        "Job " + jobId + " is missing shard " + shardNumber
                );
            }
        }
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
    }
}
