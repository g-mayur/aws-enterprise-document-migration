package io.github.awsdocmigration.engine;

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
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DynamoDbRecoveryWaveStoreIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");

    private DynamoDbClient dynamoDb;
    private DynamoDbRecoveryWaveStore store;
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

        tableName = "recovery-waves-" + UUID.randomUUID().toString().replace("-", "");
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

        store = new DynamoDbRecoveryWaveStore(dynamoDb, tableName);
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
    void auditRecordAndWaveUnlockAreCommittedAtomically() {
        RecoveryWaveSnapshot snapshot = new RecoveryWaveSnapshot(
                "audit-job", "audit-wave", 1, "TERMINAL", "SUCCEEDED",
                "arn:execution:audit", "arn:stateMachine:audit", "a".repeat(64),
                List.of(0), NOW
        );
        RecoveryReconciliationAudit audit = new RecoveryReconciliationAudit(
                "audit-job", "audit-wave", 1, NOW.plusSeconds(5), "SUCCEEDED",
                1, 1, 0, 0, 0, 0, "COMPLETED"
        );

        assertTrue(store.markReconciled(snapshot, audit));
        assertFalse(store.markReconciled(snapshot, audit),
                "A second commit for the same wave must be rejected");

        String auditKey = String.format("RECOVERY_RECONCILIATION#%020d#%013d",
                audit.generation(), audit.reconciledAt().toEpochMilli());
        var persisted = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(java.util.Map.of(
                        "pk", software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder()
                                .s("JOB#audit-job").build(),
                        "sk", software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder()
                                .s(auditKey).build()))
                .consistentRead(true)
                .build()).item();
        assertEquals("audit-wave", persisted.get("waveId").s());
        assertEquals("COMPLETED", persisted.get("finalizationStatus").s());
        assertEquals("1", persisted.get("completedShards").n());

        RecoveryWaveLease next = store.acquire(
                "audit-job", "next-wave", "next-owner", List.of(0),
                "next-execution", NOW.plusSeconds(6)
        ).orElseThrow();
        assertEquals(2, next.generation());
    }

    @Test
    void concurrentReconciliationCommitsAllowOnlyOneAuditRecord() throws Exception {
        RecoveryWaveSnapshot snapshot = new RecoveryWaveSnapshot(
                "concurrent-audit-job", "wave-1", 1, "TERMINAL", "FAILED",
                "arn:execution:concurrent", "arn:stateMachine:concurrent", "b".repeat(64),
                List.of(0), NOW
        );
        RecoveryReconciliationAudit audit = new RecoveryReconciliationAudit(
                "concurrent-audit-job", "wave-1", 1, NOW.plusSeconds(7), "FAILED",
                1, 0, 1, 0, 0, 0, "WAITING_FOR_SHARDS"
        );
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return store.markReconciled(snapshot, audit);
            });
            var second = executor.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return store.markReconciled(snapshot, audit);
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            int committed = (first.get(20, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(20, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, committed);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentAcquisitionAllowsExactlyOneOwnerAndUnknownStatusKeepsSlotLocked() throws Exception {
        int contenders = 12;
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(contenders);
        try {
            List<java.util.concurrent.Future<java.util.Optional<RecoveryWaveLease>>> futures =
                    new ArrayList<>();
            for (int index = 0; index < contenders; index++) {
                final int contender = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting for concurrent start");
                    }
                    return store.acquire(
                            "job-race", "wave-" + contender, "owner-" + contender,
                            List.of(0, 2), "recovery-job-race-" + contender, NOW
                    );
                }));
            }

            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<RecoveryWaveLease> acquired = new ArrayList<>();
            for (var future : futures) {
                future.get(20, TimeUnit.SECONDS).ifPresent(acquired::add);
            }
            assertEquals(1, acquired.size(), "Only one coordinator may own a job recovery wave");

            RecoveryWaveLease winner = acquired.get(0);
            assertTrue(store.acquire(
                    "job-race", "wave-extra", "owner-extra", List.of(0),
                    "recovery-job-race-extra", NOW.plusSeconds(1)
            ).isEmpty(), "UNKNOWN execution status must retain the job-wide lock");

            assertTrue(store.recordExecutionStatus(
                    winner, "arn:aws:states:region:account:execution:state-machine:wave",
                    "SUCCEEDED", NOW.plusSeconds(2)
            ));

            assertTrue(store.acquire(
                    "job-race", "wave-next", "owner-next", List.of(1),
                    "recovery-job-race-next", NOW.plusSeconds(3)
            ).isEmpty(), "A terminal execution remains locked until shard reconciliation is recorded");

            RecoveryWaveSnapshot terminalWave = store.get("job-race").orElseThrow();
            assertTrue(store.markReconciled(terminalWave, NOW.plusSeconds(3)));

            RecoveryWaveLease next = store.acquire(
                    "job-race", "wave-next", "owner-next", List.of(1),
                    "recovery-job-race-next", NOW.plusSeconds(4)
            ).orElseThrow();
            assertEquals(winner.generation() + 1, next.generation());
        } finally {
            executor.shutdownNow();
        }
    }
}
