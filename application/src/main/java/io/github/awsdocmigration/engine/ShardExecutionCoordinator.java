package io.github.awsdocmigration.engine;

import io.github.awsdocmigration.domain.MigrationJobStatus;
import io.github.awsdocmigration.domain.MigrationShardStatus;
import io.github.awsdocmigration.domain.DocumentRecord;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Executes the records assigned to one durable migration shard.
 *
 * The shard is claimed before the manifest is read. Its fencing-token lease
 * is renewed while the manifest is scanned and documents are migrated.
 * Only the current lease owner may mark the shard terminal.
 */
public final class ShardExecutionCoordinator {

    @FunctionalInterface
    public interface MigrationOperation {
        DocumentMigrationCoordinator.MigrationOutcome migrate(
                DocumentRecord record
        ) throws Exception;
    }

    public record Result(
            int shardNumber,
            long assigned,
            long migrated,
            long alreadyCompleted
    ) {
    }

    private final JobStateStore jobStateStore;
    private final DocumentStateStore documentStateStore;
    private final String jobId;
    private final int shardNumber;
    private final String workerId;
    private final Duration shardLeaseDuration;
    private final Duration documentLeaseDuration;
    private final int maximumDocumentAttempts;

    public ShardExecutionCoordinator(
            JobStateStore jobStateStore,
            DocumentStateStore documentStateStore,
            String jobId,
            int shardNumber,
            String workerId,
            Duration shardLeaseDuration,
            Duration documentLeaseDuration,
            int maximumDocumentAttempts
    ) {
        this.jobStateStore = Objects.requireNonNull(
                jobStateStore, "Job state store is required"
        );
        this.documentStateStore = Objects.requireNonNull(
                documentStateStore, "Document state store is required"
        );
        requireText(jobId, "jobId");
        requireText(workerId, "workerId");

        if (shardNumber < 0) {
            throw new IllegalArgumentException("shardNumber cannot be negative");
        }
        validateLeaseDuration(shardLeaseDuration, "shardLeaseDuration");
        validateLeaseDuration(documentLeaseDuration, "documentLeaseDuration");
        if (maximumDocumentAttempts < 1) {
            throw new IllegalArgumentException(
                    "maximumDocumentAttempts must be at least 1"
            );
        }

        this.jobId = jobId;
        this.shardNumber = shardNumber;
        this.workerId = workerId;
        this.shardLeaseDuration = shardLeaseDuration;
        this.documentLeaseDuration = documentLeaseDuration;
        this.maximumDocumentAttempts = maximumDocumentAttempts;
    }

    /**
     * Claims and executes one shard from the supplied JSONL manifest.
     *
     * A document that is already completed is counted and skipped. A
     * document that cannot be claimed for any other reason fails the shard;
     * it is never silently counted as migrated.
     */
    public Result execute(
            Path manifestPath,
            MigrationOperation migration
    ) throws Exception {
        Objects.requireNonNull(manifestPath, "Manifest path is required");
        Objects.requireNonNull(migration, "Migration operation is required");

        ensureJobRunning();

        Optional<MigrationJobState> job = jobStateStore.getJob(jobId);
        int totalShards = job.orElseThrow(() -> new IllegalStateException(
                "Migration job does not exist: " + jobId
        )).totalShards();

        if (totalShards < 1 || shardNumber >= totalShards) {
            throw new IllegalArgumentException(
                    "Shard number " + shardNumber
                            + " is outside the job's shard range [0, "
                            + totalShards + ")"
            );
        }

        ShardLease initialLease = jobStateStore.claimShard(
                jobId,
                shardNumber,
                workerId,
                Instant.now(),
                shardLeaseDuration
        ).orElseThrow(() -> new IllegalStateException(
                "Shard " + shardNumber
                        + " is already owned, completed, or not claimable"
        ));

        AtomicReference<ShardLease> currentLease =
                new AtomicReference<>(initialLease);
        AtomicBoolean leaseLost = new AtomicBoolean(false);
        AtomicReference<RuntimeException> renewalFailure =
                new AtomicReference<>();

        ScheduledExecutorService heartbeat =
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(
                            runnable, "shard-lease-heartbeat-" + shardNumber
                    );
                    thread.setDaemon(true);
                    return thread;
                });

        long renewalIntervalMillis = Math.max(
                1_000L, shardLeaseDuration.toMillis() / 3
        );

        heartbeat.scheduleAtFixedRate(() -> {
            if (leaseLost.get()) {
                return;
            }

            try {
                Optional<ShardLease> renewed =
                        jobStateStore.renewShardLease(
                                currentLease.get(),
                                Instant.now(),
                                shardLeaseDuration
                        );

                if (renewed.isPresent()) {
                    currentLease.set(renewed.get());
                } else {
                    leaseLost.set(true);
                }
            } catch (RuntimeException failure) {
                renewalFailure.compareAndSet(null, failure);
                leaseLost.set(true);
            }
        }, renewalIntervalMillis, renewalIntervalMillis,
                TimeUnit.MILLISECONDS);

        long assigned = 0;
        long migrated = 0;
        long alreadyCompleted = 0;

        try {
            DocumentMigrationCoordinator documentCoordinator =
                    new DocumentMigrationCoordinator(
                            documentStateStore,
                            jobId,
                            shardNumber,
                            workerId,
                            documentLeaseDuration,
                            maximumDocumentAttempts
                    );

            try (ManifestReader reader = new ManifestReader(manifestPath)) {
                for (DocumentRecord record : reader) {
                    if (ShardAssignment.shardFor(record, totalShards)
                            != shardNumber) {
                        continue;
                    }

                    assigned++;

                    if (leaseLost.get()) {
                        throw leaseLostException(renewalFailure.get());
                    }

                    DocumentMigrationCoordinator.Result documentResult =
                            documentCoordinator.execute(
                                    record,
                                    () -> migration.migrate(record)
                            );

                    switch (documentResult) {
                        case COMPLETED -> migrated++;
                        case ALREADY_COMPLETED -> alreadyCompleted++;
                        case NOT_CLAIMED -> throw new IllegalStateException(
                                "Document could not be claimed and is not "
                                        + "confirmed completed: "
                                        + record.idempotencyKey()
                        );
                    }
                }
            }

            stopHeartbeat(heartbeat);

            if (leaseLost.get()) {
                throw leaseLostException(renewalFailure.get());
            }

            boolean completed = jobStateStore.transitionClaimedShard(
                    currentLease.get(),
                    MigrationShardStatus.COMPLETED,
                    Instant.now(),
                    null
            );

            if (!completed) {
                throw new IllegalStateException(
                        "Shard completion rejected because this worker no "
                                + "longer owns the shard lease"
                );
            }

            return new Result(
                    shardNumber, assigned, migrated, alreadyCompleted
            );

        } catch (Exception failure) {
            stopHeartbeat(heartbeat);

            if (!leaseLost.get()) {
                try {
                    boolean markedFailed =
                            jobStateStore.transitionClaimedShard(
                                    currentLease.get(),
                                    MigrationShardStatus.FAILED,
                                    Instant.now(),
                                    failure.getMessage()
                            );

                    if (!markedFailed) {
                        failure.addSuppressed(new IllegalStateException(
                                "Shard failure was not recorded because "
                                        + "the worker no longer owns its lease"
                        ));
                    }
                } catch (RuntimeException stateFailure) {
                    failure.addSuppressed(stateFailure);
                }
            }

            throw failure;
        }
    }

    private void ensureJobRunning() {
        MigrationJobState job = jobStateStore.getJob(jobId)
                .orElseThrow(() -> new IllegalStateException(
                        "Migration job does not exist: " + jobId
                ));

        if (job.status() == MigrationJobStatus.READY) {
            boolean transitioned = jobStateStore.transitionJob(
                    jobId,
                    MigrationJobStatus.READY,
                    MigrationJobStatus.RUNNING,
                    Instant.now(),
                    null
            );

            if (!transitioned) {
                job = jobStateStore.getJob(jobId)
                        .orElseThrow(() -> new IllegalStateException(
                                "Migration job disappeared while starting"
                        ));
            } else {
                return;
            }
        }

        if (job.status() != MigrationJobStatus.RUNNING) {
            throw new IllegalStateException(
                    "Cannot execute a shard while job is " + job.status()
            );
        }
    }

    private static IllegalStateException leaseLostException(
            RuntimeException renewalFailure
    ) {
        return new IllegalStateException(
                "Shard lease was lost during execution; this worker cannot "
                        + "complete or fail the shard",
                renewalFailure
        );
    }

    private static void stopHeartbeat(
            ScheduledExecutorService heartbeat
    ) {
        heartbeat.shutdownNow();
        try {
            if (!heartbeat.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "Shard lease heartbeat did not stop promptly"
                );
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while stopping shard lease heartbeat",
                    interrupted
            );
        }
    }

    private static void validateLeaseDuration(
            Duration duration,
            String name
    ) {
        if (duration == null || duration.toMillis() < 3_000) {
            throw new IllegalArgumentException(
                    name + " must be at least 3 seconds"
            );
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
