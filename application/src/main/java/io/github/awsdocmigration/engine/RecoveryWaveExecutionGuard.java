package io.github.awsdocmigration.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.DescribeExecutionRequest;
import software.amazon.awssdk.services.sfn.model.ListExecutionsRequest;
import software.amazon.awssdk.services.sfn.model.ExecutionStatus;

import java.io.IOException;
import java.util.Objects;

/**
 * Read-only preflight guard that refuses recovery dispatch while the supplied
 * state machine has a running execution for the same job.
 *
 * ListExecutions is eventually consistent, so the operator must also confirm
 * that the previous execution is terminal before dispatching a recovery wave.
 */
public final class RecoveryWaveExecutionGuard {

    private static final ObjectMapper JSON = new ObjectMapper();

    private RecoveryWaveExecutionGuard() {
    }

    public static void requireNoRunningExecutionForJob(
            SfnClient stepFunctions,
            String stateMachineArn,
            String jobId
    ) {
        Objects.requireNonNull(stepFunctions, "Step Functions client is required");
        requireText(stateMachineArn, "stateMachineArn");
        requireText(jobId, "jobId");

        String nextToken = null;
        do {
            ListExecutionsRequest.Builder request = ListExecutionsRequest.builder()
                    .stateMachineArn(stateMachineArn)
                    .statusFilter(ExecutionStatus.RUNNING)
                    .maxResults(1000);
            if (nextToken != null) {
                request.nextToken(nextToken);
            }

            final var page;
            try {
                page = stepFunctions.listExecutions(request.build());
            } catch (SdkException failure) {
                throw new IllegalStateException(
                        "Unable to verify existing Step Functions executions; recovery dispatch is blocked.",
                        failure);
            }

            for (var execution : page.executions()) {
                if (execution.executionArn() == null || execution.executionArn().isBlank()) {
                    throw new IllegalStateException(
                            "A running Step Functions execution has no ARN; recovery dispatch is blocked.");
                }

                final String input;
                try {
                    input = stepFunctions.describeExecution(DescribeExecutionRequest.builder()
                            .executionArn(execution.executionArn())
                            .build()).input();
                } catch (SdkException failure) {
                    throw new IllegalStateException(
                            "Unable to inspect a running Step Functions execution; recovery dispatch is blocked.",
                            failure);
                }

                final JsonNode root;
                try {
                    root = JSON.readTree(input);
                } catch (IOException | RuntimeException invalidInput) {
                    throw new IllegalStateException(
                            "A running execution has unreadable input; recovery dispatch is blocked.",
                            invalidInput);
                }
                if (root == null || !root.isObject() || !root.hasNonNull("jobId")) {
                    throw new IllegalStateException(
                            "A running execution has no readable jobId; recovery dispatch is blocked.");
                }
                if (jobId.equals(root.path("jobId").asText())) {
                    throw new IllegalStateException(
                            "A Step Functions execution is already running for job " + jobId
                                    + "; wait for it to become terminal before dispatching recovery.");
                }
            }
            nextToken = page.nextToken();
        } while (nextToken != null && !nextToken.isBlank());
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
