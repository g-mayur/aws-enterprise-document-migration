package io.github.awsdocmigration.engine;

import io.github.awsdocmigration.domain.DocumentMigrationStatus;
import io.github.awsdocmigration.domain.VersionIdentityType;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * DynamoDB implementation of the document state contract.
 *
 * The existing state table uses pk/sk string keys. Each job/shard is a
 * partition and each document identity is a deterministically hashed item.
 * Legacy identities retain the original hash input so existing state remains
 * addressable after this upgrade.
 */
public final class DynamoDbDocumentStateStore implements DocumentStateStore {

    private static final int MAX_ERROR_LENGTH = 2_000;

    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public DynamoDbDocumentStateStore(DynamoDbClient dynamoDb, String tableName) {
        this.dynamoDb = Objects.requireNonNull(
                dynamoDb,
                "DynamoDB client is required"
        );

        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException(
                    "DynamoDB state table name is required"
            );
        }

        this.tableName = tableName;
    }

    @Override
    public boolean claim(
            String jobId,
            int shardNumber,
            String documentId,
            String versionId,
            VersionIdentityType versionIdentityType,
            String workerId,
            Instant now,
            Duration leaseDuration,
            int maximumAttempts
    ) {
        validateIdentity(jobId, shardNumber, documentId, versionId, versionIdentityType);
        requireText(workerId, "workerId");
        Objects.requireNonNull(now, "Current time is required");
        Objects.requireNonNull(leaseDuration, "Lease duration is required");

        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException(
                    "Lease duration must be greater than zero"
            );
        }
        if (maximumAttempts < 1) {
            throw new IllegalArgumentException(
                    "maximumAttempts must be at least 1"
            );
        }

        long nowMillis = now.toEpochMilli();
        long leaseExpiry = Math.addExact(nowMillis, leaseDuration.toMillis());

        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":inProgress", string(DocumentMigrationStatus.IN_PROGRESS.name()));
        values.put(":completed", string(DocumentMigrationStatus.COMPLETED.name()));
        values.put(":worker", string(workerId));
        values.put(":now", number(nowMillis));
        values.put(":leaseExpiry", number(leaseExpiry));
        values.put(":zero", number(0));
        values.put(":one", number(1));
        values.put(":maxAttempts", number(maximumAttempts));
        values.put(":documentId", string(documentId));
        values.put(":versionId", string(versionId));
        values.put(":versionIdentityType", string(versionIdentityType.name()));

        UpdateItemRequest request = UpdateItemRequest.builder()
                .tableName(tableName)
                .key(key(jobId, shardNumber, documentId, versionId, versionIdentityType))
                .updateExpression(
                        "SET #status = :inProgress, "
                                + "#leaseOwner = :worker, "
                                + "#leaseExpiresAt = :leaseExpiry, "
                                + "#attemptCount = if_not_exists(#attemptCount, :zero) + :one, "
                                + "#updatedAt = :now, "
                                + "#documentId = :documentId, "
                                + "#versionId = :versionId, "
                                + "#versionIdentityType = :versionIdentityType"
                )
                .conditionExpression(
                        "(attribute_not_exists(#status) OR "
                                + "(#status <> :completed AND "
                                + "(#status <> :inProgress OR #leaseExpiresAt <= :now))) "
                                + "AND (attribute_not_exists(#attemptCount) "
                                + "OR #attemptCount < :maxAttempts)"
                )
                .expressionAttributeNames(claimAttributeNames())
                .expressionAttributeValues(values)
                .returnValues(ReturnValue.NONE)
                .build();

        try {
            dynamoDb.updateItem(request);
            return true;
        } catch (ConditionalCheckFailedException notClaimable) {
            return false;
        }
    }

    @Override
    public boolean renewLease(
            String jobId,
            int shardNumber,
            String documentId,
            String versionId,
            VersionIdentityType versionIdentityType,
            String workerId,
            Instant now,
            Duration leaseDuration
    ) {
        validateIdentity(jobId, shardNumber, documentId, versionId, versionIdentityType);
        requireText(workerId, "workerId");
        Objects.requireNonNull(now, "Current time is required");
        Objects.requireNonNull(leaseDuration, "Lease duration is required");

        long leaseDurationMillis = leaseDuration.toMillis();
        if (leaseDurationMillis <= 0) {
            throw new IllegalArgumentException(
                    "Lease duration must be at least one millisecond"
            );
        }

        long nowMillis = now.toEpochMilli();
        long leaseExpiry = Math.addExact(nowMillis, leaseDurationMillis);

        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":inProgress", string(DocumentMigrationStatus.IN_PROGRESS.name()));
        values.put(":worker", string(workerId));
        values.put(":now", number(nowMillis));
        values.put(":leaseExpiry", number(leaseExpiry));

        UpdateItemRequest request = UpdateItemRequest.builder()
                .tableName(tableName)
                .key(key(jobId, shardNumber, documentId, versionId, versionIdentityType))
                .updateExpression(
                        "SET #leaseExpiresAt = :leaseExpiry, #updatedAt = :now"
                )
                .conditionExpression(
                        "#status = :inProgress AND #leaseOwner = :worker "
                                + "AND #leaseExpiresAt > :now"
                )
                .expressionAttributeNames(leaseAttributeNames())
                .expressionAttributeValues(values)
                .returnValues(ReturnValue.NONE)
                .build();

        return updateOnlyIfLeaseOwned(request);
    }

    @Override
    public boolean markCompleted(
            String jobId,
            int shardNumber,
            String documentId,
            String versionId,
            VersionIdentityType versionIdentityType,
            String workerId,
            String destinationKey,
            String sha256,
            Instant now
    ) {
        validateIdentity(jobId, shardNumber, documentId, versionId, versionIdentityType);
        requireText(workerId, "workerId");
        requireText(destinationKey, "destinationKey");
        requireSha256(sha256);
        Objects.requireNonNull(now, "Current time is required");

        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":inProgress", string(DocumentMigrationStatus.IN_PROGRESS.name()));
        values.put(":completed", string(DocumentMigrationStatus.COMPLETED.name()));
        values.put(":worker", string(workerId));
        values.put(":destinationKey", string(destinationKey));
        values.put(":checksum", string(sha256.toLowerCase()));
        values.put(":now", number(now.toEpochMilli()));

        UpdateItemRequest request = UpdateItemRequest.builder()
                .tableName(tableName)
                .key(key(jobId, shardNumber, documentId, versionId, versionIdentityType))
                .updateExpression(
                        "SET #status = :completed, "
                                + "#destinationKey = :destinationKey, "
                                + "#checksum = :checksum, "
                                + "#updatedAt = :now "
                                + "REMOVE #leaseOwner, #leaseExpiresAt, #lastError"
                )
                .conditionExpression(
                        "#status = :inProgress AND #leaseOwner = :worker "
                                + "AND #leaseExpiresAt > :now"
                )
                .expressionAttributeNames(completionAttributeNames())
                .expressionAttributeValues(values)
                .returnValues(ReturnValue.NONE)
                .build();

        return updateOnlyIfLeaseOwned(request);
    }

    @Override
    public boolean markFailed(
            String jobId,
            int shardNumber,
            String documentId,
            String versionId,
            VersionIdentityType versionIdentityType,
            String workerId,
            String errorMessage,
            Instant now
    ) {
        validateIdentity(jobId, shardNumber, documentId, versionId, versionIdentityType);
        requireText(workerId, "workerId");
        Objects.requireNonNull(now, "Current time is required");

        String safeError = errorMessage == null || errorMessage.isBlank()
                ? "Unspecified migration failure"
                : errorMessage.substring(0, Math.min(errorMessage.length(), MAX_ERROR_LENGTH));

        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":inProgress", string(DocumentMigrationStatus.IN_PROGRESS.name()));
        values.put(":failed", string(DocumentMigrationStatus.FAILED.name()));
        values.put(":worker", string(workerId));
        values.put(":error", string(safeError));
        values.put(":now", number(now.toEpochMilli()));

        UpdateItemRequest request = UpdateItemRequest.builder()
                .tableName(tableName)
                .key(key(jobId, shardNumber, documentId, versionId, versionIdentityType))
                .updateExpression(
                        "SET #status = :failed, "
                                + "#lastError = :error, "
                                + "#updatedAt = :now "
                                + "REMOVE #leaseOwner, #leaseExpiresAt"
                )
                .conditionExpression(
                        "#status = :inProgress AND #leaseOwner = :worker "
                                + "AND #leaseExpiresAt > :now"
                )
                .expressionAttributeNames(failureAttributeNames())
                .expressionAttributeValues(values)
                .returnValues(ReturnValue.NONE)
                .build();

        return updateOnlyIfLeaseOwned(request);
    }

    @Override
    public Optional<DocumentMigrationState> get(
            String jobId,
            int shardNumber,
            String documentId,
            String versionId,
            VersionIdentityType versionIdentityType
    ) {
        validateIdentity(jobId, shardNumber, documentId, versionId, versionIdentityType);

        GetItemResponse response = dynamoDb.getItem(
                GetItemRequest.builder()
                        .tableName(tableName)
                        .key(key(jobId, shardNumber, documentId, versionId, versionIdentityType))
                        .consistentRead(true)
                        .build()
        );

        if (response == null || !response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }

        Map<String, AttributeValue> item = response.item();
        AttributeValue storedIdentityType = item.get("versionIdentityType");
        VersionIdentityType identityType = storedIdentityType == null
                || storedIdentityType.s() == null
                ? VersionIdentityType.LEGACY_UNSPECIFIED
                : VersionIdentityType.valueOf(storedIdentityType.s());

        return Optional.of(new DocumentMigrationState(
                text(item, "documentId"),
                text(item, "versionId"),
                DocumentMigrationStatus.valueOf(text(item, "status")),
                integer(item, "attemptCount"),
                nullableText(item, "leaseOwner"),
                nullableLong(item, "leaseExpiresAt"),
                nullableText(item, "destinationKey"),
                nullableText(item, "checksum"),
                nullableText(item, "lastError"),
                longValue(item, "updatedAt"),
                identityType
        ));
    }

    private boolean updateOnlyIfLeaseOwned(UpdateItemRequest request) {
        try {
            UpdateItemResponse ignored = dynamoDb.updateItem(request);
            return true;
        } catch (ConditionalCheckFailedException leaseNotOwned) {
            return false;
        }
    }

    private Map<String, AttributeValue> key(
            String jobId,
            int shardNumber,
            String documentId,
            String versionId,
            VersionIdentityType versionIdentityType
    ) {
        Map<String, AttributeValue> key = new HashMap<>();
        key.put("pk", string("JOB#" + jobId + "#SHARD#" + shardNumber));
        key.put("sk", string("DOC#" + identityHash(documentId, versionId, versionIdentityType)));
        return key;
    }

    private static Map<String, String> claimAttributeNames() {
        Map<String, String> names = new HashMap<>();
        names.put("#status", "status");
        names.put("#leaseOwner", "leaseOwner");
        names.put("#leaseExpiresAt", "leaseExpiresAt");
        names.put("#attemptCount", "attemptCount");
        names.put("#updatedAt", "updatedAt");
        names.put("#documentId", "documentId");
        names.put("#versionId", "versionId");
        names.put("#versionIdentityType", "versionIdentityType");
        return names;
    }

    private static Map<String, String> leaseAttributeNames() {
        Map<String, String> names = new HashMap<>();
        names.put("#status", "status");
        names.put("#leaseOwner", "leaseOwner");
        names.put("#leaseExpiresAt", "leaseExpiresAt");
        names.put("#updatedAt", "updatedAt");
        return names;
    }

    private static Map<String, String> completionAttributeNames() {
        Map<String, String> names = new HashMap<>();
        names.put("#status", "status");
        names.put("#destinationKey", "destinationKey");
        names.put("#checksum", "checksum");
        names.put("#updatedAt", "updatedAt");
        names.put("#leaseOwner", "leaseOwner");
        names.put("#leaseExpiresAt", "leaseExpiresAt");
        names.put("#lastError", "lastError");
        return names;
    }

    private static Map<String, String> failureAttributeNames() {
        Map<String, String> names = new HashMap<>();
        names.put("#status", "status");
        names.put("#lastError", "lastError");
        names.put("#updatedAt", "updatedAt");
        names.put("#leaseOwner", "leaseOwner");
        names.put("#leaseExpiresAt", "leaseExpiresAt");
        return names;
    }

    /**
     * Preserve the original hash for legacy records so their existing state
     * remains addressable. New identity types use a namespaced hash input.
     */
    private static String identityHash(
            String documentId,
            String versionId,
            VersionIdentityType versionIdentityType
    ) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String identity = versionIdentityType == VersionIdentityType.LEGACY_UNSPECIFIED
                    ? documentId + "\u0000" + versionId
                    : versionIdentityType.name() + "\u0000" + documentId + "\u0000" + versionId;
            byte[] input = identity.getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(digest.digest(input));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue number(long value) {
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
        return Math.toIntExact(longValue(item, name));
    }

    private static long longValue(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null || value.n() == null ? 0L : Long.parseLong(value.n());
    }

    private static Long nullableLong(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null || value.n() == null ? null : Long.valueOf(value.n());
    }

    private static void validateIdentity(
            String jobId,
            int shardNumber,
            String documentId,
            String versionId,
            VersionIdentityType versionIdentityType
    ) {
        requireText(jobId, "jobId");
        requireText(documentId, "documentId");
        requireText(versionId, "versionId");
        Objects.requireNonNull(versionIdentityType, "Version identity type is required");

        if (shardNumber < 0) {
            throw new IllegalArgumentException("shardNumber cannot be negative");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    private static void requireSha256(String value) {
        if (value == null || !value.matches("[A-Fa-f0-9]{64}")) {
            throw new IllegalArgumentException(
                    "sha256 must contain exactly 64 hexadecimal characters"
            );
        }
    }
}
