package io.github.awsdocmigration.engine;

import io.github.awsdocmigration.domain.DocumentMigrationStatus;
import io.github.awsdocmigration.domain.DocumentRecord;
import io.github.awsdocmigration.domain.VersionIdentityType;

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
 * Coordinates one document migration with the durable state store.
 *
 * A lease is renewed while the supplied migration operation is running.
 * The operation must not be considered successful unless the same worker
 * can conditionally mark the document completed after the upload.
 */
public final class DocumentMigrationCoordinator {

    public enum Result {
        COMPLETED,
        ALREADY_COMPLETED,
        NOT_CLAIMED
    }

    public record MigrationOutcome(String destinationKey, String sha256) {
        public MigrationOutcome {
            if (destinationKey == null || destinationKey.isBlank()) {
                throw new IllegalArgumentException("Destination key is required");
            }
            if (sha256 == null || !sha256.matches("[A-Fa-f0-9]{64}")) {
                throw new IllegalArgumentException(
                        "A valid SHA-256 checksum is required"
                );
            }
        }
    }

    private final DocumentStateStore stateStore;
    private final String jobId;
    private final int shardNumber;
    private final String workerId;
    private final Duration leaseDuration;
    private final int maximumAttempts;

    public DocumentMigrationCoordinator(
            DocumentStateStore stateStore,
            String jobId,
            int shardNumber,
            String workerId,
            Duration leaseDuration,
            int maximumAttempts
    ) {
        this.stateStore = Objects.requireNonNull(
                stateStore,
                "Document state store is required"
        );
        requireText(jobId, "jobId");
        requireText(workerId, "workerId");

        if (shardNumber < 0) {
            throw new IllegalArgumentException("shardNumber cannot be negative");
        }
        if (leaseDuration == null || leaseDuration.toMillis() < 3_000) {
            throw new IllegalArgumentException(
                    "Lease duration must be at least 3 seconds"
            );
        }
        if (maximumAttempts < 1) {
            throw new IllegalArgumentException(
                    "maximumAttempts must be at least 1"
            );
        }

        this.jobId = jobId;
        this.shardNumber = shardNumber;
        this.workerId = workerId;
        this.leaseDuration = leaseDuration;
        this.maximumAttempts = maximumAttempts;
    }

    /**
     * Claims, executes, renews and records one document migration.
     *
     * @return COMPLETED, ALREADY_COMPLETED, or NOT_CLAIMED
     */
    public Result execute(
            DocumentRecord record,
            Callable<MigrationOutcome> migration
    ) throws Exception {
        Objects.requireNonNull(record, "Document record is required");
        Objects.requireNonNull(migration, "Migration operation is required");

        VersionIdentityType identityType = record.versionIdentityType();
        Instant claimTime = Instant.now();

        boolean claimed = stateStore.claim(
                jobId,
                shardNumber,
                record.documentId(),
                record.versionId(),
                identityType,
                workerId,
                claimTime,
                leaseDuration,
                maximumAttempts
        );

        if (!claimed) {
            Optional<DocumentMigrationState> existing = stateStore.get(
                    jobId,
                    shardNumber,
                    record.documentId(),
                    record.versionId(),
                    identityType
            );

            if (existing.isPresent()
                    && existing.get().status() == DocumentMigrationStatus.COMPLETED) {
                return Result.ALREADY_COMPLETED;
            }

            return Result.NOT_CLAIMED;
        }

        AtomicBoolean leaseLost = new AtomicBoolean(false);
        AtomicReference<RuntimeException> renewalFailure = new AtomicReference<>();

        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(
                runnable -> {
                    Thread thread = new Thread(runnable, "document-lease-heartbeat");
                    thread.setDaemon(true);
                    return thread;
                }
        );

        long renewalIntervalMillis = Math.max(
                1_000L,
                leaseDuration.toMillis() / 3
        );

        heartbeat.scheduleAtFixedRate(() -> {
            if (leaseLost.get()) {
                return;
            }

            try {
                boolean renewed = stateStore.renewLease(
                        jobId,
                        shardNumber,
                        record.documentId(),
                        record.versionId(),
                        identityType,
                        workerId,
                        Instant.now(),
                        leaseDuration
                );

                if (!renewed) {
                    leaseLost.set(true);
                }
            } catch (RuntimeException failure) {
                renewalFailure.compareAndSet(null, failure);
                leaseLost.set(true);
            }
        }, renewalIntervalMillis, renewalIntervalMillis, TimeUnit.MILLISECONDS);

        try {
            MigrationOutcome outcome = migration.call();

            if (leaseLost.get()) {
                throw new IllegalStateException(
                        "Document lease was lost before migration completion",
                        renewalFailure.get()
                );
            }

            boolean completed = stateStore.markCompleted(
                    jobId,
                    shardNumber,
                    record.documentId(),
                    record.versionId(),
                    identityType,
                    workerId,
                    outcome.destinationKey(),
                    outcome.sha256(),
                    Instant.now()
            );

            if (!completed) {
                throw new IllegalStateException(
                        "Unable to mark document completed because the worker no longer owns its lease"
                );
            }

            return Result.COMPLETED;

        } catch (Exception failure) {
            try {
                boolean markedFailed = stateStore.markFailed(
                        jobId,
                        shardNumber,
                        record.documentId(),
                        record.versionId(),
                        identityType,
                        workerId,
                        failure.getMessage(),
                        Instant.now()
                );

                if (!markedFailed) {
                    failure.addSuppressed(new IllegalStateException(
                            "Failure state was not recorded because the worker no longer owns its lease"
                    ));
                }
            } catch (RuntimeException stateFailure) {
                failure.addSuppressed(stateFailure);
            }

            throw failure;

        } finally {
            heartbeat.shutdownNow();
        }
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
    }
}
