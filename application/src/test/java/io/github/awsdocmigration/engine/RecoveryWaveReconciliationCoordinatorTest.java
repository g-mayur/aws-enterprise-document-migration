package io.github.awsdocmigration.engine;

import io.github.awsdocmigration.domain.MigrationJobStatus;
import io.github.awsdocmigration.domain.MigrationShardStatus;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.DescribeExecutionResponse;
import software.amazon.awssdk.services.sfn.model.DescribeExecutionRequest;
import software.amazon.awssdk.services.sfn.model.ExecutionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecoveryWaveReconciliationCoordinatorTest {

    private static final Instant NOW = Instant.parse("2026-11-16T04:00:00Z");

    @Test
    void refusesToReconcileWaveThatIsNotDurablyTerminal() {
        SfnClient sfn = mock(SfnClient.class);
        DynamoDbRecoveryWaveStore waves = mock(DynamoDbRecoveryWaveStore.class);
        JobStateStore jobs = mock(JobStateStore.class);
        when(waves.get("job-1")).thenReturn(Optional.of(wave("ACTIVE", "RUNNING")));

        assertThrows(IllegalStateException.class,
                () -> new RecoveryWaveReconciliationCoordinator(sfn, waves, jobs)
                        .reconcile("job-1", NOW));
        verifyNoInteractions(sfn);
        verifyNoInteractions(jobs);
    }

    @Test
    void terminalExecutionDoesNotFinalizeWhileDurableShardIsStillPending() {
        SfnClient sfn = mock(SfnClient.class);
        DynamoDbRecoveryWaveStore waves = mock(DynamoDbRecoveryWaveStore.class);
        JobStateStore jobs = mock(JobStateStore.class);
        when(waves.get("job-1")).thenReturn(Optional.of(wave("TERMINAL", "FAILED")));
        when(waves.markReconciled(any(), eq(NOW))).thenReturn(true);
        when(sfn.describeExecution(any(DescribeExecutionRequest.class))).thenReturn(DescribeExecutionResponse.builder()
                .executionArn("arn:aws:states:us-east-1:123456789012:execution:flow:exec-1")
                .stateMachineArn("arn:aws:states:us-east-1:123456789012:stateMachine:flow")
                .status(ExecutionStatus.FAILED)
                .build());
        MigrationJobState running = job(MigrationJobStatus.RUNNING);
        when(jobs.getJob("job-1")).thenReturn(Optional.of(running));
        when(jobs.listShards("job-1")).thenReturn(List.of(
                shard(0, MigrationShardStatus.PENDING, 0)
        ));

        RecoveryWaveReconciliationCoordinator.Result result =
                new RecoveryWaveReconciliationCoordinator(sfn, waves, jobs)
                        .reconcile("job-1", NOW);

        assertEquals("WAITING_FOR_SHARDS", result.finalizationStatus());
        assertEquals(1, result.retryableShards());
        assertEquals(0, result.completedShards());
        verify(jobs, never()).transitionJob(anyString(), any(), any(), any(), any());
    }

    @Test
    void refusesWhenDescribedExecutionStatusDiffersFromPersistedTerminalStatus() {
        SfnClient sfn = mock(SfnClient.class);
        DynamoDbRecoveryWaveStore waves = mock(DynamoDbRecoveryWaveStore.class);
        JobStateStore jobs = mock(JobStateStore.class);
        when(waves.get("job-1")).thenReturn(Optional.of(wave("TERMINAL", "SUCCEEDED")));
        when(sfn.describeExecution(any(DescribeExecutionRequest.class))).thenReturn(DescribeExecutionResponse.builder()
                .executionArn("arn:aws:states:us-east-1:123456789012:execution:flow:exec-1")
                .stateMachineArn("arn:aws:states:us-east-1:123456789012:stateMachine:flow")
                .status(ExecutionStatus.RUNNING)
                .build());

        assertThrows(IllegalStateException.class,
                () -> new RecoveryWaveReconciliationCoordinator(sfn, waves, jobs)
                        .reconcile("job-1", NOW));
        verifyNoInteractions(jobs);
    }

    private static RecoveryWaveSnapshot wave(String state, String executionStatus) {
        return new RecoveryWaveSnapshot(
                "job-1", "wave-1", 1, state, executionStatus,
                "arn:aws:states:us-east-1:123456789012:execution:flow:exec-1",
                "arn:aws:states:us-east-1:123456789012:stateMachine:flow",
                "abc123", List.of(0), NOW
        );
    }

    private static MigrationJobState job(MigrationJobStatus status) {
        return new MigrationJobState("job-1", status, 1, null,
                NOW.toEpochMilli(), NOW.toEpochMilli(), null);
    }

    private static MigrationShardState shard(
            int number, MigrationShardStatus status, int attempts
    ) {
        return new MigrationShardState("job-1", number, status, null, null,
                attempts, NOW.toEpochMilli(), NOW.toEpochMilli());
    }
}
