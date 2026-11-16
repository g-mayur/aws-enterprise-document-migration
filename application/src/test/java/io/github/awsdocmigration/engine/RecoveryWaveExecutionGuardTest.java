package io.github.awsdocmigration.engine;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.DescribeExecutionRequest;
import software.amazon.awssdk.services.sfn.model.DescribeExecutionResponse;
import software.amazon.awssdk.services.sfn.model.ExecutionListItem;
import software.amazon.awssdk.services.sfn.model.ListExecutionsRequest;
import software.amazon.awssdk.services.sfn.model.ListExecutionsResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RecoveryWaveExecutionGuardTest {

    private static final String ARN =
            "arn:aws:states:us-east-1:123456789012:stateMachine:migration";
    private static final String EXECUTION_ARN =
            "arn:aws:states:us-east-1:123456789012:execution:migration:execution-1";

    @Test
    void blocksWhenSameJobAlreadyHasRunningExecution() {
        SfnClient sfn = mock(SfnClient.class);
        when(sfn.listExecutions(any(ListExecutionsRequest.class)))
                .thenReturn(ListExecutionsResponse.builder()
                        .executions(ExecutionListItem.builder().executionArn(EXECUTION_ARN).build())
                        .build());
        when(sfn.describeExecution(any(DescribeExecutionRequest.class)))
                .thenReturn(DescribeExecutionResponse.builder().input("{\"jobId\":\"job-1\"}").build());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> RecoveryWaveExecutionGuard.requireNoRunningExecutionForJob(sfn, ARN, "job-1"));

        assertTrue(failure.getMessage().contains("already running"));
        verify(sfn).describeExecution(any(DescribeExecutionRequest.class));
    }

    @Test
    void allowsRunningExecutionsForDifferentJobs() {
        SfnClient sfn = mock(SfnClient.class);
        when(sfn.listExecutions(any(ListExecutionsRequest.class)))
                .thenReturn(ListExecutionsResponse.builder()
                        .executions(ExecutionListItem.builder().executionArn(EXECUTION_ARN).build())
                        .build());
        when(sfn.describeExecution(any(DescribeExecutionRequest.class)))
                .thenReturn(DescribeExecutionResponse.builder().input("{\"jobId\":\"another-job\"}").build());

        assertDoesNotThrow(
                () -> RecoveryWaveExecutionGuard.requireNoRunningExecutionForJob(sfn, ARN, "job-1"));
    }

    @Test
    void allowsWhenThereAreNoRunningExecutions() {
        SfnClient sfn = mock(SfnClient.class);
        when(sfn.listExecutions(any(ListExecutionsRequest.class)))
                .thenReturn(ListExecutionsResponse.builder().executions(java.util.List.of()).build());

        assertDoesNotThrow(
                () -> RecoveryWaveExecutionGuard.requireNoRunningExecutionForJob(sfn, ARN, "job-1"));
        verify(sfn, never()).describeExecution(any(DescribeExecutionRequest.class));
    }

    @Test
    void failsClosedWhenExecutionCannotBeInspected() {
        SfnClient sfn = mock(SfnClient.class);
        when(sfn.listExecutions(any(ListExecutionsRequest.class)))
                .thenReturn(ListExecutionsResponse.builder()
                        .executions(ExecutionListItem.builder().executionArn(EXECUTION_ARN).build())
                        .build());
        when(sfn.describeExecution(any(DescribeExecutionRequest.class)))
                .thenThrow(SdkClientException.create("temporary failure"));

        assertThrows(IllegalStateException.class,
                () -> RecoveryWaveExecutionGuard.requireNoRunningExecutionForJob(sfn, ARN, "job-1"));
    }
}
