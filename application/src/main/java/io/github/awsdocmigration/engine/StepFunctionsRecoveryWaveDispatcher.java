package io.github.awsdocmigration.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.DescribeExecutionResponse;
import software.amazon.awssdk.services.sfn.model.ExecutionAlreadyExistsException;
import software.amazon.awssdk.services.sfn.model.ExecutionListItem;
import software.amazon.awssdk.services.sfn.model.ExecutionStatus;
import software.amazon.awssdk.services.sfn.model.ListExecutionsRequest;
import software.amazon.awssdk.services.sfn.model.ListExecutionsResponse;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;
import software.amazon.awssdk.services.sfn.model.StartExecutionResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Starts or resolves one deterministic Step Functions execution for a
 * previously acquired recovery wave.
 *
 * This class deliberately fails closed: if StartExecution has an ambiguous
 * outcome and the execution cannot be found and described, the DynamoDB wave
 * remains ACTIVE/UNKNOWN. It never declares an execution nonexistent based
 * only on an eventually consistent ListExecutions response.
 */
public final class StepFunctionsRecoveryWaveDispatcher {

    private static final ObjectMapper JSON = new ObjectMapper();

    public enum Outcome {
        RUNNING,
        TERMINAL,
        UNKNOWN,
        INPUT_MISMATCH,
        STALE_OWNER
    }

    public record Result(Outcome outcome, String executionArn, String executionStatus) {
        public Result {
            Objects.requireNonNull(outcome, "Dispatch outcome is required");
            Objects.requireNonNull(executionStatus, "Execution status is required");
        }
    }

    private final SfnClient stepFunctions;
    private final DynamoDbRecoveryWaveStore waveStore;

    public StepFunctionsRecoveryWaveDispatcher(
            SfnClient stepFunctions,
            DynamoDbRecoveryWaveStore waveStore
    ) {
        this.stepFunctions = Objects.requireNonNull(stepFunctions, "Step Functions client is required");
        this.waveStore = Objects.requireNonNull(waveStore, "Recovery wave store is required");
    }

    /**
     * Binds the exact request before starting. Repeated attempts use the same
     * execution name and exact input, allowing Standard Step Functions
     * StartExecution idempotency to prevent duplicate active executions.
     */
    public Result dispatch(
            RecoveryWaveLease lease,
            String stateMachineArn,
            String exactInputJson,
            Instant now
    ) {
        Objects.requireNonNull(lease, "Recovery wave lease is required");
        Objects.requireNonNull(now, "Dispatch time is required");
        requireText(stateMachineArn, "stateMachineArn");
        requireText(exactInputJson, "exactInputJson");
        validateInputMatchesLease(lease, exactInputJson);

        String inputDigest = sha256(exactInputJson);
        if (!waveStore.bindExecutionRequest(lease, stateMachineArn, inputDigest, now)) {
            return new Result(Outcome.UNKNOWN, null, "UNKNOWN");
        }

        try {
            StartExecutionResponse started = stepFunctions.startExecution(StartExecutionRequest.builder()
                    .stateMachineArn(stateMachineArn)
                    .name(lease.executionName())
                    .input(exactInputJson)
                    .build());
            return describeAndPersist(lease, started.executionArn(), exactInputJson, now);
        } catch (ExecutionAlreadyExistsException alreadyExists) {
            return resolveByDeterministicName(lease, stateMachineArn, exactInputJson, now);
        } catch (SdkException ambiguousOrRejected) {
            // A transport timeout can occur after AWS accepted the request.
            // Resolve by deterministic name; if that cannot be proved, retain
            // UNKNOWN rather than releasing the recovery-wave lock.
            return resolveByDeterministicName(lease, stateMachineArn, exactInputJson, now);
        }
    }

    private Result resolveByDeterministicName(
            RecoveryWaveLease lease,
            String stateMachineArn,
            String expectedInput,
            Instant now
    ) {
        try {
            String nextToken = null;
            do {
                ListExecutionsRequest.Builder request = ListExecutionsRequest.builder()
                        .stateMachineArn(stateMachineArn)
                        .maxResults(1000);
                if (nextToken != null) {
                    request.nextToken(nextToken);
                }
                ListExecutionsResponse page = stepFunctions.listExecutions(request.build());
                Optional<ExecutionListItem> match = page.executions().stream()
                        .filter(item -> lease.executionName().equals(item.name()))
                        .findFirst();
                if (match.isPresent()) {
                    return describeAndPersist(
                            lease, match.get().executionArn(), expectedInput, now
                    );
                }
                nextToken = page.nextToken();
            } while (nextToken != null && !nextToken.isBlank());
        } catch (SdkException lookupFailure) {
            // The original StartExecution outcome is still ambiguous.
        }
        return persistUnknown(lease, null, now);
    }

    private Result describeAndPersist(
            RecoveryWaveLease lease,
            String executionArn,
            String expectedInput,
            Instant now
    ) {
        if (executionArn == null || executionArn.isBlank()) {
            return persistUnknown(lease, null, now);
        }

        final DescribeExecutionResponse described;
        try {
            described = stepFunctions.describeExecution(builder -> builder.executionArn(executionArn));
        } catch (SdkException describeFailure) {
            return persistUnknown(lease, executionArn, now);
        }

        if (!expectedInput.equals(described.input())) {
            boolean recorded = waveStore.recordExecutionStatus(
                    lease, executionArn, "UNKNOWN", now
            );
            return new Result(
                    recorded ? Outcome.INPUT_MISMATCH : Outcome.STALE_OWNER,
                    executionArn,
                    "UNKNOWN"
            );
        }

        String status = mapStatus(described.status());
        if ("UNKNOWN".equals(status)) {
            return persistUnknown(lease, executionArn, now);
        }

        boolean recorded = waveStore.recordExecutionStatus(lease, executionArn, status, now);
        if (!recorded) {
            return new Result(Outcome.STALE_OWNER, executionArn, status);
        }
        Outcome outcome = "RUNNING".equals(status) ? Outcome.RUNNING : Outcome.TERMINAL;
        return new Result(outcome, executionArn, status);
    }

    private Result persistUnknown(RecoveryWaveLease lease, String executionArn, Instant now) {
        boolean recorded = waveStore.recordExecutionStatus(lease, executionArn, "UNKNOWN", now);
        return new Result(
                recorded ? Outcome.UNKNOWN : Outcome.STALE_OWNER,
                executionArn,
                "UNKNOWN"
        );
    }

    private static String mapStatus(ExecutionStatus status) {
        if (status == null) {
            return "UNKNOWN";
        }
        return switch (status) {
            case RUNNING -> "RUNNING";
            case SUCCEEDED -> "SUCCEEDED";
            case FAILED -> "FAILED";
            case TIMED_OUT -> "TIMED_OUT";
            case ABORTED -> "ABORTED";
            default -> "UNKNOWN";
        };
    }

    private static void validateInputMatchesLease(RecoveryWaveLease lease, String input) {
        final JsonNode root;
        try {
            root = JSON.readTree(input);
        } catch (IOException invalidJson) {
            throw new IllegalArgumentException("Step Functions input must be valid JSON", invalidJson);
        }
        if (root == null || !root.isObject()
                || !lease.jobId().equals(root.path("jobId").asText(null))) {
            throw new IllegalArgumentException("Step Functions input jobId must match the recovery wave");
        }

        JsonNode shards = root.path("shards");
        if (!shards.isArray() || shards.isEmpty()) {
            throw new IllegalArgumentException("Step Functions input must contain a non-empty shards array");
        }
        List<Integer> supplied = new ArrayList<>();
        for (JsonNode shard : shards) {
            JsonNode number = shard.path("shardNumber");
            if (!number.isIntegralNumber() || !number.canConvertToInt() || number.intValue() < 0) {
                throw new IllegalArgumentException("Every selected shard must have a non-negative shardNumber");
            }
            supplied.add(number.intValue());
        }
        supplied.sort(Integer::compareTo);
        if (!supplied.equals(lease.selectedShardNumbers())) {
            throw new IllegalArgumentException("Step Functions shard set does not match the recovery wave");
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
