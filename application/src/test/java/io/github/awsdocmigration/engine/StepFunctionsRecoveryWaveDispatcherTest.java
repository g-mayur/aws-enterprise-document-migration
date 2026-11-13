package io.github.awsdocmigration.engine;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.DescribeExecutionRequest;
import software.amazon.awssdk.services.sfn.model.DescribeExecutionResponse;
import software.amazon.awssdk.services.sfn.model.ExecutionAlreadyExistsException;
import software.amazon.awssdk.services.sfn.model.ExecutionListItem;
import software.amazon.awssdk.services.sfn.model.ExecutionStatus;
import software.amazon.awssdk.services.sfn.model.ListExecutionsRequest;
import software.amazon.awssdk.services.sfn.model.ListExecutionsResponse;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;
import software.amazon.awssdk.services.sfn.model.StartExecutionResponse;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StepFunctionsRecoveryWaveDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");
    private static final String STATE_MACHINE_ARN =
            "arn:aws:states:us-east-1:123456789012:stateMachine:migration";
    private static final String EXECUTION_ARN =
            "arn:aws:states:us-east-1:123456789012:execution:migration:wave-1";
    private static final String INPUT =
            "{\"jobId\":\"job-1\",\"shards\":[{\"shardNumber\":0}]}";

    @Test
    void startsAndPersistsAuthoritativelyDescribedTerminalExecution() {
        SfnClient sfn = mock(SfnClient.class);
        DynamoDbRecoveryWaveStore store = mock(DynamoDbRecoveryWaveStore.class);
        RecoveryWaveLease lease = lease();
        StepFunctionsRecoveryWaveDispatcher dispatcher =
                new StepFunctionsRecoveryWaveDispatcher(sfn, store);

        when(store.bindExecutionRequest(any(), any(), any(), any())).thenReturn(true);
        when(sfn.startExecution(any(StartExecutionRequest.class)))
                .thenReturn(StartExecutionResponse.builder().executionArn(EXECUTION_ARN).build());
        when(sfn.describeExecution(any(DescribeExecutionRequest.class)))
                .thenReturn(DescribeExecutionResponse.builder()
                        .executionArn(EXECUTION_ARN)
                        .input(INPUT)
                        .status(ExecutionStatus.SUCCEEDED)
                        .build());
        when(store.recordExecutionStatus(lease, EXECUTION_ARN, "SUCCEEDED", NOW)).thenReturn(true);

        StepFunctionsRecoveryWaveDispatcher.Result result =
                dispatcher.dispatch(lease, STATE_MACHINE_ARN, INPUT, NOW);

        assertEquals(StepFunctionsRecoveryWaveDispatcher.Outcome.TERMINAL, result.outcome());
        assertEquals("SUCCEEDED", result.executionStatus());
        assertEquals(EXECUTION_ARN, result.executionArn());
        verify(sfn).startExecution(any(StartExecutionRequest.class));
        verify(store).recordExecutionStatus(lease, EXECUTION_ARN, "SUCCEEDED", NOW);
    }

    @Test
    void resolvesExecutionAlreadyStartedByDeterministicName() {
        SfnClient sfn = mock(SfnClient.class);
        DynamoDbRecoveryWaveStore store = mock(DynamoDbRecoveryWaveStore.class);
        RecoveryWaveLease lease = lease();
        StepFunctionsRecoveryWaveDispatcher dispatcher =
                new StepFunctionsRecoveryWaveDispatcher(sfn, store);

        when(store.bindExecutionRequest(any(), any(), any(), any())).thenReturn(true);
        when(sfn.startExecution(any(StartExecutionRequest.class)))
                .thenThrow(ExecutionAlreadyExistsException.builder().message("already exists").build());
        when(sfn.listExecutions(any(ListExecutionsRequest.class)))
                .thenReturn(ListExecutionsResponse.builder()
                        .executions(ExecutionListItem.builder()
                                .name("wave-1")
                                .executionArn(EXECUTION_ARN)
                                .build())
                        .build());
        when(sfn.describeExecution(any(DescribeExecutionRequest.class)))
                .thenReturn(DescribeExecutionResponse.builder()
                        .executionArn(EXECUTION_ARN)
                        .input(INPUT)
                        .status(ExecutionStatus.RUNNING)
                        .build());
        when(store.recordExecutionStatus(lease, EXECUTION_ARN, "RUNNING", NOW)).thenReturn(true);

        StepFunctionsRecoveryWaveDispatcher.Result result =
                dispatcher.dispatch(lease, STATE_MACHINE_ARN, INPUT, NOW);

        assertEquals(StepFunctionsRecoveryWaveDispatcher.Outcome.RUNNING, result.outcome());
        assertEquals("RUNNING", result.executionStatus());
        verify(sfn).listExecutions(any(ListExecutionsRequest.class));
        verify(store).recordExecutionStatus(lease, EXECUTION_ARN, "RUNNING", NOW);
    }

    @Test
    void leavesWaveUnknownWhenStartTimesOutAndExecutionCannotBeFound() {
        SfnClient sfn = mock(SfnClient.class);
        DynamoDbRecoveryWaveStore store = mock(DynamoDbRecoveryWaveStore.class);
        RecoveryWaveLease lease = lease();
        StepFunctionsRecoveryWaveDispatcher dispatcher =
                new StepFunctionsRecoveryWaveDispatcher(sfn, store);

        when(store.bindExecutionRequest(any(), any(), any(), any())).thenReturn(true);
        when(sfn.startExecution(any(StartExecutionRequest.class)))
                .thenThrow(SdkClientException.create("request timed out"));
        when(sfn.listExecutions(any(ListExecutionsRequest.class)))
                .thenReturn(ListExecutionsResponse.builder().executions(List.of()).build());
        when(store.recordExecutionStatus(lease, null, "UNKNOWN", NOW)).thenReturn(true);

        StepFunctionsRecoveryWaveDispatcher.Result result =
                dispatcher.dispatch(lease, STATE_MACHINE_ARN, INPUT, NOW);

        assertEquals(StepFunctionsRecoveryWaveDispatcher.Outcome.UNKNOWN, result.outcome());
        assertEquals("UNKNOWN", result.executionStatus());
        verify(store).recordExecutionStatus(lease, null, "UNKNOWN", NOW);
        verify(store, never()).recordExecutionStatus(lease, null, "NONEXISTENT", NOW);
    }

    @Test
    void rejectsInputThatDoesNotMatchSelectedShardSetBeforeCallingAws() {
        SfnClient sfn = mock(SfnClient.class);
        DynamoDbRecoveryWaveStore store = mock(DynamoDbRecoveryWaveStore.class);
        StepFunctionsRecoveryWaveDispatcher dispatcher =
                new StepFunctionsRecoveryWaveDispatcher(sfn, store);

        assertThrows(IllegalArgumentException.class, () -> dispatcher.dispatch(
                lease(), STATE_MACHINE_ARN,
                "{\"jobId\":\"job-1\",\"shards\":[{\"shardNumber\":1}]}",
                NOW
        ));

        verify(sfn, never()).startExecution(any(StartExecutionRequest.class));
        verify(store, never()).bindExecutionRequest(any(), any(), any(), any());
    }

    private static RecoveryWaveLease lease() {
        return new RecoveryWaveLease(
                "job-1", "wave-id-1", "owner-1", 1,
                List.of(0), "digest", "wave-1", NOW
        );
    }
}
