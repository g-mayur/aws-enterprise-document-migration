package io.github.awsdocmigration.engine;

import io.github.awsdocmigration.domain.DocumentMigrationStatus;
import io.github.awsdocmigration.domain.VersionIdentityType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises the DynamoDB state store against a real DynamoDB API implementation.
 *
 * The test is enabled in CI by DYNAMODB_LOCAL_ENDPOINT. It is skipped during
 * ordinary local Maven runs when that environment variable is not configured.
 */
class DynamoDbDocumentStateStoreIntegrationTest {

    private static final Instant BASE_TIME =
            Instant.parse("2026-11-13T10:00:00Z");

    private DynamoDbClient dynamoDb;
    private DynamoDbDocumentStateStore store;
    private String tableName;

    @BeforeEach
    void setUp() {
        String endpoint = System.getenv("DYNAMODB_LOCAL_ENDPOINT");
        assumeTrue(
                endpoint != null && !endpoint.isBlank(),
                "Set DYNAMODB_LOCAL_ENDPOINT to run DynamoDB integration tests"
        );

        dynamoDb = DynamoDbClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("local", "local")
                ))
                .build();

        tableName = "migration-state-" +
                UUID.randomUUID().toString().replace("-", "");

        dynamoDb.createTable(CreateTableRequest.builder()
                .tableName(tableName)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        AttributeDefinition.builder()
                                .attributeName("pk")
                                .attributeType(ScalarAttributeType.S)
                                .build(),
                        AttributeDefinition.builder()
                                .attributeName("sk")
                                .attributeType(ScalarAttributeType.S)
                                .build()
                )
                .keySchema(
                        KeySchemaElement.builder()
                                .attributeName("pk")
                                .keyType(KeyType.HASH)
                                .build(),
                        KeySchemaElement.builder()
                                .attributeName("sk")
                                .keyType(KeyType.RANGE)
                                .build()
                )
                .build());

        store = new DynamoDbDocumentStateStore(dynamoDb, tableName);
    }

    @AfterEach
    void tearDown() {
        if (dynamoDb != null) {
            try {
                if (tableName != null) {
                    dynamoDb.deleteTable(DeleteTableRequest.builder()
                            .tableName(tableName)
                            .build());
                }
            } finally {
                dynamoDb.close();
            }
        }
    }

    @Test
    void onlyOneConcurrentWorkerCanClaimTheSameDocument() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);

        try {
            Future<Boolean> first = workers.submit(() -> {
                start.await();
                return claim("worker-a", BASE_TIME, 3);
            });

            Future<Boolean> second = workers.submit(() -> {
                start.await();
                return claim("worker-b", BASE_TIME, 3);
            });

            start.countDown();

            boolean firstClaimed = first.get();
            boolean secondClaimed = second.get();

            assertNotEquals(
                    firstClaimed,
                    secondClaimed,
                    "Exactly one worker must acquire the document lease"
            );
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void anotherWorkerCanClaimAfterLeaseExpires() {
        assertTrue(claim("worker-a", BASE_TIME, 3));

        assertTrue(claim(
                "worker-b",
                BASE_TIME.plus(Duration.ofMinutes(6)),
                3
        ));

        assertFalse(claim(
                "worker-a",
                BASE_TIME.plus(Duration.ofMinutes(6)),
                3
        ));
    }

    @Test
    void maximumAttemptCountIsEnforcedByDynamoDb() {
        assertTrue(claim("worker-a", BASE_TIME, 2));

        assertTrue(store.markFailed(
                "job-integration",
                0,
                "document-integration",
                "version-integration",
                VersionIdentityType.SOURCE_VERSION,
                "worker-a",
                "first attempt failed",
                BASE_TIME.plusSeconds(1)
        ));

        Instant secondAttemptTime = BASE_TIME.plus(Duration.ofMinutes(6));
        assertTrue(claim("worker-b", secondAttemptTime, 2));

        assertTrue(store.markFailed(
                "job-integration",
                0,
                "document-integration",
                "version-integration",
                VersionIdentityType.SOURCE_VERSION,
                "worker-b",
                "second attempt failed",
                secondAttemptTime.plusSeconds(1)
        ));

        assertFalse(claim(
                "worker-c",
                secondAttemptTime.plus(Duration.ofMinutes(6)),
                2
        ));
    }

    @Test
    void staleWorkerCannotCompleteAfterLeaseHasBeenReclaimed() {
        assertTrue(claim("worker-a", BASE_TIME, 3));

        Instant reclaimedAt = BASE_TIME.plus(Duration.ofMinutes(6));
        assertTrue(claim("worker-b", reclaimedAt, 3));

        String checksum = "a".repeat(64);

        assertFalse(store.markCompleted(
                "job-integration",
                0,
                "document-integration",
                "version-integration",
                VersionIdentityType.SOURCE_VERSION,
                "worker-a",
                "documents/document-integration.pdf",
                checksum,
                reclaimedAt.plusSeconds(1)
        ));

        assertTrue(store.markCompleted(
                "job-integration",
                0,
                "document-integration",
                "version-integration",
                VersionIdentityType.SOURCE_VERSION,
                "worker-b",
                "documents/document-integration.pdf",
                checksum,
                reclaimedAt.plusSeconds(2)
        ));

        dynamoDb.close();
        dynamoDb = DynamoDbClient.builder()
                .endpointOverride(URI.create(System.getenv("DYNAMODB_LOCAL_ENDPOINT")))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("local", "local")
                ))
                .build();
        store = new DynamoDbDocumentStateStore(dynamoDb, tableName);

        DocumentMigrationState persisted = store.get(
                "job-integration",
                0,
                "document-integration",
                "version-integration",
                VersionIdentityType.SOURCE_VERSION
        ).orElseThrow();

        assertEquals(DocumentMigrationStatus.COMPLETED, persisted.status());
        assertEquals("documents/document-integration.pdf", persisted.destinationKey());
        assertEquals(checksum, persisted.checksum());
        assertNull(persisted.leaseOwner());
    }

    private boolean claim(String workerId, Instant now, int maximumAttempts) {
        return store.claim(
                "job-integration",
                0,
                "document-integration",
                "version-integration",
                VersionIdentityType.SOURCE_VERSION,
                workerId,
                now,
                Duration.ofMinutes(5),
                maximumAttempts
        );
    }
}
