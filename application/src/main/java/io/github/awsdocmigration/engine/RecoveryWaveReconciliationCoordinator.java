package io.github.awsdocmigration.engine;

import io.github.awsdocmigration.domain.MigrationJobStatus;
import io.github.awsdocmigration.domain.MigrationShardStatus;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.DescribeExecutionRequest;
import software.amazon.awssdk.services.sfn.model.ExecutionStatus;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Reconciles a terminal recovery execution against strongly consistent shard
 * state, then delegates the final transition to JobFinalizationCoordinator.
 *
 * A terminal Step Functions status is necessary but never sufficient for job
 * finalization. The durable shard registry remains authoritative.
 */
public final class RecoveryWaveReconciliationCoordinator {

    private static final List<String> TERMINAL_STATUSES =
            List.of("SUCCEEDED", "FAILED", "TIMED_OUT", "ABORTED", "NONEXISTENT");

    private final SfnClient stepFunctions;
    private final DynamoDbRecoveryWaveStore waveStore;
    private final JobStateStore jobStateStore;
    private final JobFinalizationCoordinator finalizer;

    public RecoveryWaveReconciliationCoordinator(
            SfnClient stepFunctions,
            DynamoDbRecoveryWaveStore waveStore,
            JobStateStore jobStateStore
    ) {
        this.stepFunctions = Objects.requireNonNull(stepFunctions, "Step Functions client is required");
        this.waveStore = Objects.requireNonNull(waveStore, "Recovery wave store is required");
        this.jobStateStore = Objects.requireNonNull(jobStateStore, "Job state store is required");
        this.finalizer = new JobFinalizationCoordinator(jobStateStore);
    }

    public Result reconcile(String jobId, Instant inspectedAt) {
        requireText(jobId, "jobId");
        Objects.requireNonNull(inspectedAt, "Inspection time is required");

        RecoveryWaveSnapshot wave = waveStore.get(jobId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No recovery wave is registered for job " + jobId));

        if (!"TERMINAL".equals(wave.state())
                || !TERMINAL_STATUSES.contains(wave.executionStatus())) {
            throw new IllegalStateException(
                    "Recovery wave is not durably confirmed terminal; state="
                            + wave.state() + " executionStatus=" + wave.executionStatus()
                            + ". Reconciliation is blocked.");
        }
        if (wave.executionArn() == null || wave.stateMachineArn() == null) {
            throw new IllegalStateException(
                    "Recovery wave has no verified execution ARN or state-machine ARN; reconciliation is blocked.");
        }

        var execution = stepFunctions.describeExecution(DescribeExecutionRequest.builder()
                .executionArn(wave.executionArn())
                .build());
        if (!wave.executionArn().equals(execution.executionArn())) {
            throw new IllegalStateException("Step Functions returned a different execution ARN; reconciliation is blocked.");
        }
        if (!wave.stateMachineArn().equals(execution.stateMachineArn())) {
            throw new IllegalStateException("Execution belongs to a different state machine; reconciliation is blocked.");
        }

        String observedStatus = mapStatus(execution.status());
        if (!TERMINAL_STATUSES.contains(observedStatus)
                || !wave.executionStatus().equals(observedStatus)) {
            throw new IllegalStateException(
                    "Persisted execution status " + wave.executionStatus()
                            + " does not match the authoritative Step Functions status "
                            + observedStatus + "; reconciliation is blocked.");
        }

        MigrationJobState job = jobStateStore.getJob(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown migration job: " + jobId));
        for (Integer shardNumber : wave.selectedShardNumbers()) {
            if (shardNumber >= job.totalShards()) {
                throw new IllegalStateException(
                        "Recovery wave references shard " + shardNumber
                                + " outside the prepared job shard range.");
            }
        }

        List<MigrationShardState> shards = jobStateStore.listShards(jobId);
        Map<ShardRecoveryInspector.Disposition, Integer> counts =
                new EnumMap<>(ShardRecoveryInspector.Disposition.class);
        for (MigrationShardState shard : shards) {
            counts.merge(ShardRecoveryInspector.assess(shard, inspectedAt), 1, Integer::sum);
        }

        Optional<MigrationJobState> finalized = finalizer.finalizeIfReady(jobId, inspectedAt);
        if (!waveStore.markReconciled(wave, inspectedAt)) {
            throw new IllegalStateException(
                    "Recovery wave changed before reconciliation could be recorded; "
                            + "the wave remains unavailable for another dispatch.");
        }
        MigrationJobState latest = jobStateStore.getJob(jobId)
                .orElseThrow(() -> new IllegalStateException("Job disappeared during reconciliation"));

        String finalization = finalized.map(state -> state.status().name())
                .orElseGet(() -> latest.status() == MigrationJobStatus.RUNNING
                        ? "WAITING_FOR_SHARDS"
                        : "NOT_READY_" + latest.status().name());

        return new Result(
                jobId,
                wave.waveId(),
                wave.generation(),
                observedStatus,
                shards.size(),
                count(counts, ShardRecoveryInspector.Disposition.COMPLETED),
                count(counts, ShardRecoveryInspector.Disposition.READY_TO_DISPATCH)
                        + count(counts, ShardRecoveryInspector.Disposition.RETRYABLE_FAILURE)
                        + count(counts, ShardRecoveryInspector.Disposition.RECLAIMABLE_EXPIRED_LEASE),
                count(counts, ShardRecoveryInspector.Disposition.WAIT_FOR_ACTIVE_LEASE),
                count(counts, ShardRecoveryInspector.Disposition.LEASE_EXPIRY_UNKNOWN),
                count(counts, ShardRecoveryInspector.Disposition.ATTEMPTS_EXHAUSTED),
                finalization
        );
    }

    private static int count(
            Map<ShardRecoveryInspector.Disposition, Integer> counts,
            ShardRecoveryInspector.Disposition disposition
    ) {
        return counts.getOrDefault(disposition, 0);
    }

    private static String mapStatus(ExecutionStatus status) {
        if (status == null) {
            return "UNKNOWN";
        }
        return switch (status) {
            case SUCCEEDED -> "SUCCEEDED";
            case FAILED -> "FAILED";
            case TIMED_OUT -> "TIMED_OUT";
            case ABORTED -> "ABORTED";
            default -> "RUNNING";
        };
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    public record Result(
            String jobId,
            String waveId,
            long generation,
            String executionStatus,
            int registeredShards,
            int completedShards,
            int retryableShards,
            int activeShards,
            int blockedShards,
            int exhaustedShards,
            String finalizationStatus
    ) {
        public Result {
            Objects.requireNonNull(jobId, "Job ID is required");
            Objects.requireNonNull(waveId, "Wave ID is required");
            Objects.requireNonNull(executionStatus, "Execution status is required");
            Objects.requireNonNull(finalizationStatus, "Finalization status is required");
        }
    }
}
