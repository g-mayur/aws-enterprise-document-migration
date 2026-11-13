package io.github.awsdocmigration.engine;

import io.github.awsdocmigration.domain.MigrationJobStatus;
import io.github.awsdocmigration.domain.MigrationShardStatus;
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
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DynamoDbJobStateStoreIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");

    private DynamoDbClient dynamoDb;
    private DynamoDbJobStateStore store;
    private String tableName;

    @BeforeEach
    void setUp() {
        String endpoint = System.getenv("DYNAMODB_LOCAL_ENDPOINT");
        assumeTrue(endpoint != null && !endpoint.isBlank(),
                "Set DYNAMODB_LOCAL_ENDPOINT to run DynamoDB integration tests");

        dynamoDb = DynamoDbClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("local", "local")))
                .build();

        tableName = "migration-jobs-" + UUID.randomUUID().toString().replace("-", "");
        dynamoDb.createTable(CreateTableRequest.builder()
                .tableName(tableName)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("pk")
                                .attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("sk")
                                .attributeType(ScalarAttributeType.S).build())
                .keySchema(
                        KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build())
                .build());

        store = new DynamoDbJobStateStore(dynamoDb, tableName);
    }

    @AfterEach
    void tearDown() {
        if (dynamoDb != null) {
            try {
                if (tableName != null) {
                    dynamoDb.deleteTable(DeleteTableRequest.builder().tableName(tableName).build());
                }
            } finally {
                dynamoDb.close();
            }
        }
    }

    @Test
    void createsJobAndAllowsOnlyValidConditionalTransitions() {
        assertTrue(store.createJob("job-1", 2, NOW));
        assertFalse(store.createJob("job-1", 2, NOW));

        assertTrue(store.transitionJob("job-1", MigrationJobStatus.CREATED,
                MigrationJobStatus.DISCOVERING, NOW.plusSeconds(1), null));
        assertFalse(store.transitionJob("job-1", MigrationJobStatus.DISCOVERING,
                MigrationJobStatus.READY, NOW.plusSeconds(2), null));

        assertTrue(store.createShard("job-1", 0, NOW.plusSeconds(2)));
        assertTrue(store.createShard("job-1", 1, NOW.plusSeconds(2)));
        assertTrue(store.transitionJob("job-1", MigrationJobStatus.DISCOVERING,
                MigrationJobStatus.READY, NOW.plusSeconds(3), null));

        MigrationJobState job = store.getJob("job-1").orElseThrow();
        assertEquals(MigrationJobStatus.READY, job.status());
        assertEquals(2, job.totalShards());
        assertEquals(NOW.toEpochMilli(), job.createdAtEpochMillis());
    }

    @Test
    void shardCreationRequiresDiscoveringJobAndIsIdempotentlyRejected() {
        assertTrue(store.createJob("job-2", 2, NOW));
        assertFalse(store.createShard("job-2", 0, NOW));

        assertTrue(store.transitionJob("job-2", MigrationJobStatus.CREATED,
                MigrationJobStatus.DISCOVERING, NOW.plusSeconds(1), null));
        assertTrue(store.createShard("job-2", 0, NOW.plusSeconds(2)));
        assertFalse(store.createShard("job-2", 0, NOW.plusSeconds(3)));

        MigrationShardState shard = store.getShard("job-2", 0).orElseThrow();
        assertEquals(MigrationShardStatus.PENDING, shard.status());
    }

    @Test
    void shardTransitionsRequireExpectedStateAndWorkerOwnership() {
        assertTrue(store.createJob("job-3", 1, NOW));
        assertTrue(store.transitionJob("job-3", MigrationJobStatus.CREATED,
                MigrationJobStatus.DISCOVERING, NOW.plusSeconds(1), null));
        assertTrue(store.createShard("job-3", 0, NOW.plusSeconds(2)));

        assertTrue(store.transitionShard("job-3", 0, MigrationShardStatus.PENDING,
                MigrationShardStatus.RUNNING, "worker-a", NOW.plusSeconds(3), null));

        assertFalse(store.transitionShard("job-3", 0, MigrationShardStatus.RUNNING,
                MigrationShardStatus.COMPLETED, "worker-b", NOW.plusSeconds(4), null));

        assertTrue(store.transitionShard("job-3", 0, MigrationShardStatus.RUNNING,
                MigrationShardStatus.COMPLETED, "worker-a", NOW.plusSeconds(5), null));

        MigrationShardState shard = store.getShard("job-3", 0).orElseThrow();
        assertEquals(MigrationShardStatus.COMPLETED, shard.status());
        assertNull(shard.workerId());
    }

    @Test
    void listsAllShardsInStableNumericOrderAndPersistsAcrossClientRecreation() {
        assertTrue(store.createJob("job-4", 11, NOW));
        assertTrue(store.transitionJob("job-4", MigrationJobStatus.CREATED,
                MigrationJobStatus.DISCOVERING, NOW.plusSeconds(1), null));
        assertTrue(store.createShard("job-4", 10, NOW.plusSeconds(2)));
        assertTrue(store.createShard("job-4", 2, NOW.plusSeconds(3)));
        assertTrue(store.createShard("job-4", 1, NOW.plusSeconds(4)));

        List<MigrationShardState> shards = store.listShards("job-4");
        assertEquals(List.of(1, 2, 10),
                shards.stream().map(MigrationShardState::shardNumber).toList());

        dynamoDb.close();
        dynamoDb = DynamoDbClient.builder()
                .endpointOverride(URI.create(System.getenv("DYNAMODB_LOCAL_ENDPOINT")))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("local", "local")))
                .build();
        store = new DynamoDbJobStateStore(dynamoDb, tableName);

        assertEquals(MigrationJobStatus.DISCOVERING,
                store.getJob("job-4").orElseThrow().status());
        assertEquals(3, store.listShards("job-4").size());
    }
}
