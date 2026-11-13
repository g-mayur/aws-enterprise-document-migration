package io.github.awsdocmigration.engine;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable job-scoped recovery-wave lock stored in the existing job-state table.
 *
 * A newly acquired wave starts in UNKNOWN execution status. That state is
 * deliberately non-terminal: a timeout around Step Functions StartExecution
 * must not allow another coordinator to launch a competing wave. A caller
 * may mark a wave terminal only after independently confirming that its
 * execution is terminal (or that the deterministic execution does not exist).
 */
public final class DynamoDbRecoveryWaveStore {

    private static final String SORT_KEY = "RECOVERY_WAVE";
    private static final List<String> TERMINAL_EXECUTION_STATUSES =
            List.of("SUCCEEDED", "FAILED", "TIMED_OUT", "ABORTED", "NONEXISTENT");

    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public DynamoDbRecoveryWaveStore(DynamoDbClient dynamoDb, String tableName) {
        this.dynamoDb = Objects.requireNonNull(dynamoDb, "DynamoDB client is required");
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("Job state table name is required");
        }
        this.tableName = tableName;
    }

    /**
     * Acquires the job's sole recovery-wave slot. A slot can be reused only
     * after its previous execution has been recorded as terminal.
     *
     * The selected shard list is normalized to ascending order and persisted
     * with a SHA-256 digest. This method does not claim or mutate shard items.
     */
    public Optional<RecoveryWaveLease> acquire(
            String jobId,
            String waveId,
            String ownerToken,
            List<Integer> selectedShardNumbers,
            String executionName,
            Instant now
    ) {
        requireText(jobId, "jobId");
        requireText(waveId, "waveId");
        requireText(ownerToken, "ownerToken");
        requireText(executionName, "executionName");
        Objects.requireNonNull(now, "Acquisition time is required");

        List<Integer> selected = normalizeShards(selectedShardNumbers);
        String digest = digest(selected);
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":zero", n(0));
        values.put(":one", n(1));
        values.put(":waveId", s(waveId));
        values.put(":ownerToken", s(ownerToken));
        values.put(":selectedShards", AttributeValue.builder()
                .l(selected.stream().map(DynamoDbRecoveryWaveStore::n).toList()).build());
        values.put(":selectedShardDigest", s(digest));
        values.put(":executionName", s(executionName));
        values.put(":active", s("ACTIVE"));
        values.put(":unknown", s("UNKNOWN"));
        values.put(":now", n(now.toEpochMilli()));
        values.put(":terminal", s("TERMINAL"));
        values.put(":succeeded", s("SUCCEEDED"));
        values.put(":failed", s("FAILED"));
        values.put(":timedOut", s("TIMED_OUT"));
        values.put(":aborted", s("ABORTED"));
        values.put(":nonexistent", s("NONEXISTENT"));

        try {
            UpdateItemResponse response = dynamoDb.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(key(jobId))
                    .updateExpression(
                            "SET #generation = if_not_exists(#generation, :zero) + :one, "
                                    + "#waveId = :waveId, #ownerToken = :ownerToken, "
                                    + "#selectedShards = :selectedShards, "
                                    + "#selectedShardDigest = :selectedShardDigest, "
                                    + "#executionName = :executionName, #state = :active, "
                                    + "#executionStatus = :unknown, #createdAt = :now, #updatedAt = :now "
                                    + "REMOVE #executionArn"
                    )
                    .conditionExpression(
                            "attribute_not_exists(pk) OR "
                                    + "(#state = :terminal AND (#executionStatus = :succeeded "
                                    + "OR #executionStatus = :failed OR #executionStatus = :timedOut "
                                    + "OR #executionStatus = :aborted OR #executionStatus = :nonexistent))"
                    )
                    .expressionAttributeNames(Map.ofEntries(
                            Map.entry("#generation", "generation"),
                            Map.entry("#waveId", "waveId"),
                            Map.entry("#ownerToken", "ownerToken"),
                            Map.entry("#selectedShards", "selectedShardNumbers"),
                            Map.entry("#selectedShardDigest", "selectedShardDigest"),
                            Map.entry("#executionName", "executionName"),
                            Map.entry("#state", "state"),
                            Map.entry("#executionStatus", "executionStatus"),
                            Map.entry("#createdAt", "createdAt"),
                            Map.entry("#updatedAt", "updatedAt"),
                            Map.entry("#executionArn", "executionArn")
                    ))
                    .expressionAttributeValues(values)
                    .returnValues("ALL_NEW")
                    .build());

            Map<String, AttributeValue> attributes = response.attributes();
            long generation = number(attributes, "generation");
            if (generation < 1) {
                throw new IllegalStateException("DynamoDB did not return the recovery wave generation");
            }
            return Optional.of(new RecoveryWaveLease(
                    jobId, waveId, ownerToken, generation, selected, digest, executionName, now
            ));
        } catch (ConditionalCheckFailedException activeWaveExists) {
            return Optional.empty();
        }
    }

    /**
     * Records the observed Step Functions execution status for the current
     * wave owner. UNKNOWN and RUNNING retain the lock. Terminal values release
     * it for a future generation. Only call with a terminal value after a
     * successful DescribeExecution check, or an authoritative not-found
     * result for the deterministic execution identity.
     */
    public boolean recordExecutionStatus(
            RecoveryWaveLease lease,
            String executionArn,
            String executionStatus,
            Instant now
    ) {
        Objects.requireNonNull(lease, "Recovery wave lease is required");
        Objects.requireNonNull(now, "Update time is required");
        requireText(executionStatus, "executionStatus");
        if (!List.of("UNKNOWN", "RUNNING", "SUCCEEDED", "FAILED",
                "TIMED_OUT", "ABORTED", "NONEXISTENT").contains(executionStatus)) {
            throw new IllegalArgumentException("Unsupported execution status: " + executionStatus);
        }
        if (executionArn != null && executionArn.isBlank()) {
            throw new IllegalArgumentException("Execution ARN cannot be blank");
        }

        boolean terminal = TERMINAL_EXECUTION_STATUSES.contains(executionStatus);
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":active", s("ACTIVE"));
        values.put(":generation", n(lease.generation()));
        values.put(":ownerToken", s(lease.ownerToken()));
        values.put(":waveId", s(lease.waveId()));
        values.put(":executionStatus", s(executionStatus));
        values.put(":updatedAt", n(now.toEpochMilli()));
        values.put(":nextState", s(terminal ? "TERMINAL" : "ACTIVE"));

        String update = "SET #executionStatus = :executionStatus, "
                + "#state = :nextState, #updatedAt = :updatedAt";
        if (executionArn == null) {
            update += " REMOVE #executionArn";
        } else {
            values.put(":executionArn", s(executionArn));
            update += ", #executionArn = :executionArn";
        }

        try {
            dynamoDb.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(key(lease.jobId()))
                    .updateExpression(update)
                    .conditionExpression(
                            "#state = :active AND #generation = :generation "
                                    + "AND #ownerToken = :ownerToken AND #waveId = :waveId"
                    )
                    .expressionAttributeNames(Map.of(
                            "#state", "state",
                            "#generation", "generation",
                            "#ownerToken", "ownerToken",
                            "#waveId", "waveId",
                            "#executionStatus", "executionStatus",
                            "#updatedAt", "updatedAt",
                            "#executionArn", "executionArn"
                    ))
                    .expressionAttributeValues(values)
                    .build());
            return true;
        } catch (ConditionalCheckFailedException staleOwner) {
            return false;
        }
    }

    private static List<Integer> normalizeShards(List<Integer> shardNumbers) {
        Objects.requireNonNull(shardNumbers, "Selected shard numbers are required");
        if (shardNumbers.isEmpty()) {
            throw new IllegalArgumentException("At least one shard must be selected");
        }
        List<Integer> selected = new ArrayList<>(shardNumbers);
        if (selected.stream().anyMatch(number -> number == null || number < 0)) {
            throw new IllegalArgumentException("Selected shard numbers must be non-negative");
        }
        selected.sort(Integer::compareTo);
        for (int index = 1; index < selected.size(); index++) {
            if (selected.get(index).equals(selected.get(index - 1))) {
                throw new IllegalArgumentException("Selected shard numbers must be unique");
            }
        }
        return List.copyOf(selected);
    }

    private static String digest(List<Integer> selected) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(selected.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static Map<String, AttributeValue> key(String jobId) {
        return Map.of(
                "pk", s("JOB#" + jobId),
                "sk", s(SORT_KEY)
        );
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue n(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }

    private static long number(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item == null ? null : item.get(name);
        return value == null || value.n() == null ? 0L : Long.parseLong(value.n());
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
