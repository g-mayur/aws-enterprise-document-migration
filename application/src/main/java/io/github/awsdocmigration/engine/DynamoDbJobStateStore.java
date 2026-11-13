package io.github.awsdocmigration.engine;

import io.github.awsdocmigration.domain.MigrationJobStatus;
import io.github.awsdocmigration.domain.MigrationShardStatus;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** DynamoDB implementation of the durable migration job and shard lifecycle. */
public final class DynamoDbJobStateStore implements JobStateStore {

    private static final int MAX_ERROR_LENGTH = 2_000;

    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public DynamoDbJobStateStore(DynamoDbClient dynamoDb, String tableName) {
        this.dynamoDb = Objects.requireNonNull(dynamoDb, "DynamoDB client is required");
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("Job state table name is required");
        }
        this.tableName = tableName;
    }

    @Override
    public boolean createJob(String jobId, int totalShards, Instant now) {
        requireText(jobId, "jobId");
        Objects.requireNonNull(now, "Creation time is required");
        if (totalShards < 0) {
            throw new IllegalArgumentException("totalShards cannot be negative");
        }

        long timestamp = now.toEpochMilli();
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("pk", s(jobKey(jobId)));
        item.put("sk", s("JOB"));
        item.put("entityType", s("JOB"));
        item.put("jobId", s(jobId));
        item.put("status", s(MigrationJobStatus.CREATED.name()));
        item.put("totalShards", n(totalShards));
        item.put("shardCount", n(0));
        item.put("createdAt", n(timestamp));
        item.put("updatedAt", n(timestamp));

        try {
            dynamoDb.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(item)
                    .conditionExpression("attribute_not_exists(pk) AND attribute_not_exists(sk)")
                    .build());
            return true;
        } catch (ConditionalCheckFailedException alreadyExists) {
            return false;
        }
    }

    @Override
    public Optional<MigrationJobState> getJob(String jobId) {
        requireText(jobId, "jobId");
        Map<String, AttributeValue> key = key(jobId, "JOB");
        Map<String, AttributeValue> item = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(key)
                .consistentRead(true)
                .build()).item();

        if (item == null || item.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new MigrationJobState(
                text(item, "jobId"),
                MigrationJobStatus.valueOf(text(item, "status")),
                integer(item, "totalShards"),
                nullableText(item, "lastError"),
                number(item, "createdAt"),
                number(item, "updatedAt")
        ));
    }

    @Override
    public boolean transitionJob(
            String jobId,
            MigrationJobStatus expected,
            MigrationJobStatus target,
            Instant now,
            String errorMessage
    ) {
        requireText(jobId, "jobId");
        Objects.requireNonNull(expected, "Expected job status is required");
        Objects.requireNonNull(target, "Target job status is required");
        Objects.requireNonNull(now, "Transition time is required");
        if (!expected.canTransitionTo(target)) {
            throw new IllegalArgumentException(
                    "Invalid job status transition: " + expected + " -> " + target
            );
        }

        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":expected", s(expected.name()));
        values.put(":target", s(target.name()));
        values.put(":now", n(now.toEpochMilli()));

        String condition = "#status = :expected";
        if (target == MigrationJobStatus.READY) {
            condition += " AND #shardCount = #totalShards";
        }

        String update = "SET #status = :target, #updatedAt = :now";
        if (target == MigrationJobStatus.FAILED && hasText(errorMessage)) {
            values.put(":error", s(safeError(errorMessage)));
            update += ", #lastError = :error";
        } else if (target != MigrationJobStatus.FAILED) {
            update += " REMOVE #lastError";
        }

        try {
            dynamoDb.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(key(jobId, "JOB"))
                    .updateExpression(update)
                    .conditionExpression(condition)
                    .expressionAttributeNames(jobTransitionNames(target, errorMessage))
                    .expressionAttributeValues(values)
                    .build());
            return true;
        } catch (ConditionalCheckFailedException staleState) {
            return false;
        }
    }

    @Override
    public boolean createShard(String jobId, int shardNumber, Instant now) {
        requireText(jobId, "jobId");
        Objects.requireNonNull(now, "Creation time is required");
        if (shardNumber < 0) {
            throw new IllegalArgumentException("shardNumber cannot be negative");
        }

        long timestamp = now.toEpochMilli();
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("pk", s(jobKey(jobId)));
        item.put("sk", s(shardKey(shardNumber)));
        item.put("entityType", s("SHARD"));
        item.put("jobId", s(jobId));
        item.put("shardNumber", n(shardNumber));
        item.put("status", s(MigrationShardStatus.PENDING.name()));
        item.put("createdAt", n(timestamp));
        item.put("updatedAt", n(timestamp));

        Map<String, AttributeValue> updateValues = Map.of(
                ":discovering", s(MigrationJobStatus.DISCOVERING.name()),
                ":one", n(1),
                ":shardNumber", n(shardNumber)
        );

        try {
            dynamoDb.transactWriteItems(TransactWriteItemsRequest.builder()
                    .transactItems(
                            TransactWriteItem.builder().update(
                                    software.amazon.awssdk.services.dynamodb.model.Update.builder()
                                            .tableName(tableName)
                                            .key(key(jobId, "JOB"))
                                            .updateExpression("ADD #shardCount :one")
                                            .conditionExpression(
                                                    "#status = :discovering AND #shardCount < #totalShards "
                                                            + "AND :shardNumber < #totalShards"
                                            )
                                            .expressionAttributeNames(Map.of(
                                                    "#status", "status",
                                                    "#shardCount", "shardCount",
                                                    "#totalShards", "totalShards"
                                            ))
                                            .expressionAttributeValues(updateValues)
                                            .build()
                            ).build(),
                            TransactWriteItem.builder().put(
                                    Put.builder()
                                            .tableName(tableName)
                                            .item(item)
                                            .conditionExpression("attribute_not_exists(pk) AND attribute_not_exists(sk)")
                                            .build()
                            ).build()
                    )
                    .build());
            return true;
        } catch (TransactionCanceledException rejected) {
            boolean conditionalFailure = rejected.cancellationReasons() != null
                    && rejected.cancellationReasons().stream()
                            .anyMatch(reason -> "ConditionalCheckFailed".equals(reason.code()));
            if (conditionalFailure) {
                return false;
            }
            throw rejected;
        }
    }

    @Override
    public Optional<MigrationShardState> getShard(String jobId, int shardNumber) {
        validateShard(jobId, shardNumber);
        Map<String, AttributeValue> item = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(key(jobId, shardKey(shardNumber)))
                .consistentRead(true)
                .build()).item();

        if (item == null || item.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toShardState(item));
    }

    @Override
    public List<MigrationShardState> listShards(String jobId) {
        requireText(jobId, "jobId");
        List<MigrationShardState> shards = new ArrayList<>();
        Map<String, AttributeValue> values = Map.of(":pk", s(jobKey(jobId)), ":prefix", s("SHARD#"));
        Map<String, String> names = Map.of("#pk", "pk", "#sk", "sk");

        dynamoDb.queryPaginator(QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("#pk = :pk AND begins_with(#sk, :prefix)")
                .expressionAttributeNames(names)
                .expressionAttributeValues(values)
                .consistentRead(true)
                .build())
                .items()
                .forEach(item -> shards.add(toShardState(item)));

        return List.copyOf(shards);
    }

    @Override
    public boolean transitionShard(
            String jobId,
            int shardNumber,
            MigrationShardStatus expected,
            MigrationShardStatus target,
            String workerId,
            Instant now,
            String errorMessage
    ) {
        validateShard(jobId, shardNumber);
        Objects.requireNonNull(expected, "Expected shard status is required");
        Objects.requireNonNull(target, "Target shard status is required");
        Objects.requireNonNull(now, "Transition time is required");
        if (!expected.canTransitionTo(target)) {
            throw new IllegalArgumentException(
                    "Invalid shard status transition: " + expected + " -> " + target
            );
        }
        if (target == MigrationShardStatus.RUNNING) {
            requireText(workerId, "workerId");
        } else if (expected == MigrationShardStatus.RUNNING) {
            requireText(workerId, "workerId");
        }

        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":expected", s(expected.name()));
        values.put(":target", s(target.name()));
        values.put(":now", n(now.toEpochMilli()));

        String update = "SET #status = :target, #updatedAt = :now";
        if (target == MigrationShardStatus.RUNNING) {
            values.put(":worker", s(workerId));
            update += ", #workerId = :worker";
        } else if (expected == MigrationShardStatus.RUNNING) {
            update += " REMOVE #workerId";
        }

        if (target == MigrationShardStatus.FAILED && hasText(errorMessage)) {
            values.put(":error", s(safeError(errorMessage)));
            update += ", #lastError = :error";
        } else if (target != MigrationShardStatus.FAILED) {
            update += " REMOVE #lastError";
        }

        String condition = "#status = :expected";
        if (expected == MigrationShardStatus.RUNNING) {
            values.put(":worker", s(workerId));
            condition += " AND #workerId = :worker";
        }

        try {
            dynamoDb.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(key(jobId, shardKey(shardNumber)))
                    .updateExpression(update)
                    .conditionExpression(condition)
                    .expressionAttributeNames(shardTransitionNames(expected, target, errorMessage))
                    .expressionAttributeValues(values)
                    .build());
            return true;
        } catch (ConditionalCheckFailedException staleState) {
            return false;
        }
    }

    private static Map<String, String> jobTransitionNames(
            MigrationJobStatus target,
            String errorMessage
    ) {
        Map<String, String> names = new HashMap<>();
        names.put("#status", "status");
        names.put("#updatedAt", "updatedAt");
        if (target == MigrationJobStatus.READY) {
            names.put("#shardCount", "shardCount");
            names.put("#totalShards", "totalShards");
        }
        if (target != MigrationJobStatus.FAILED || hasText(errorMessage)) {
            names.put("#lastError", "lastError");
        }
        return names;
    }

    private static Map<String, String> shardTransitionNames(
            MigrationShardStatus expected,
            MigrationShardStatus target,
            String errorMessage
    ) {
        Map<String, String> names = new HashMap<>();
        names.put("#status", "status");
        names.put("#updatedAt", "updatedAt");
        names.put("#workerId", "workerId");
        if (target != MigrationShardStatus.FAILED || hasText(errorMessage)) {
            names.put("#lastError", "lastError");
        }
        return names;
    }

    private static MigrationShardState toShardState(Map<String, AttributeValue> item) {
        return new MigrationShardState(
                text(item, "jobId"),
                integer(item, "shardNumber"),
                MigrationShardStatus.valueOf(text(item, "status")),
                nullableText(item, "workerId"),
                nullableText(item, "lastError"),
                number(item, "createdAt"),
                number(item, "updatedAt")
        );
    }

    private static Map<String, AttributeValue> key(String jobId, String sortKey) {
        return Map.of("pk", s(jobKey(jobId)), "sk", s(sortKey));
    }

    private static String jobKey(String jobId) {
        return "JOB#" + jobId;
    }

    private static String shardKey(int shardNumber) {
        return "SHARD#" + String.format("%08d", shardNumber);
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue n(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }

    private static String text(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null ? null : value.s();
    }

    private static String nullableText(Map<String, AttributeValue> item, String name) {
        return text(item, name);
    }

    private static int integer(Map<String, AttributeValue> item, String name) {
        return Math.toIntExact(number(item, name));
    }

    private static long number(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null || value.n() == null ? 0L : Long.parseLong(value.n());
    }

    private static String safeError(String error) {
        return error.substring(0, Math.min(error.length(), MAX_ERROR_LENGTH));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static void validateShard(String jobId, int shardNumber) {
        requireText(jobId, "jobId");
        if (shardNumber < 0) {
            throw new IllegalArgumentException("shardNumber cannot be negative");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
