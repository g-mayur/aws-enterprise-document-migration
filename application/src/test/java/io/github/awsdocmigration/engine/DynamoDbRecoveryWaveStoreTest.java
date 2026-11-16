package io.github.awsdocmigration.engine;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DynamoDbRecoveryWaveStoreTest {

    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");

    @Test
    void acquisitionPersistsSortedShardSetAndFailsClosedWhileExecutionStatusIsUnknown() {
        DynamoDbClient dynamoDb = mock(DynamoDbClient.class);
        when(dynamoDb.updateItem(any(UpdateItemRequest.class))).thenReturn(
                UpdateItemResponse.builder().attributes(Map.of(
                        "generation", AttributeValue.builder().n("1").build()
                )).build()
        );
        DynamoDbRecoveryWaveStore store = new DynamoDbRecoveryWaveStore(dynamoDb, "job-state");

        RecoveryWaveLease lease = store.acquire(
                "job-1", "wave-1", "owner-1", List.of(7, 2, 4),
                "recovery-job-1-1", NOW
        ).orElseThrow();

        assertEquals(1, lease.generation());
        assertEquals(List.of(2, 4, 7), lease.selectedShardNumbers());
        assertEquals(64, lease.selectedShardDigest().length());
        assertTrue(lease.executionName().matches("recovery-[0-9a-f]{32}"));

        ArgumentCaptor<UpdateItemRequest> request = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamoDb).updateItem(request.capture());
        UpdateItemRequest captured = request.getValue();
        assertTrue(captured.conditionExpression().contains("attribute_not_exists(pk)"));
        assertTrue(captured.conditionExpression().contains("#executionStatus = :nonexistent"));
        assertTrue(captured.conditionExpression().contains("#reconciliationStatus = :reconciled"));
        assertTrue(captured.updateExpression().contains("#executionStatus = :unknown"));
        assertEquals("ALL_NEW", captured.returnValuesAsString());
        assertEquals(List.of("2", "4", "7"),
                captured.expressionAttributeValues().get(":selectedShards").l().stream()
                        .map(AttributeValue::n).toList());
    }

    @Test
    void activeWaveConflictReturnsEmptyWithoutChangingOwnership() {
        DynamoDbClient dynamoDb = mock(DynamoDbClient.class);
        when(dynamoDb.updateItem(any(UpdateItemRequest.class))).thenThrow(
                ConditionalCheckFailedException.builder().message("condition failed").build()
        );
        DynamoDbRecoveryWaveStore store = new DynamoDbRecoveryWaveStore(dynamoDb, "job-state");

        assertTrue(store.acquire(
                "job-1", "wave-2", "owner-2", List.of(1), "recovery-job-1-2", NOW
        ).isEmpty());
    }

    @Test
    void executionStatusUpdateIsFencedByGenerationOwnerAndWave() {
        DynamoDbClient dynamoDb = mock(DynamoDbClient.class);
        DynamoDbRecoveryWaveStore store = new DynamoDbRecoveryWaveStore(dynamoDb, "job-state");
        RecoveryWaveLease lease = new RecoveryWaveLease(
                "job-1", "wave-1", "owner-1", 3, List.of(1, 2),
                "a".repeat(64), "recovery-job-1-3", NOW
        );

        assertTrue(store.recordExecutionStatus(lease, "arn:execution:1", "RUNNING", NOW.plusSeconds(1)));

        ArgumentCaptor<UpdateItemRequest> request = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamoDb).updateItem(request.capture());
        UpdateItemRequest captured = request.getValue();
        assertTrue(captured.conditionExpression().contains("#generation = :generation"));
        assertTrue(captured.conditionExpression().contains("#ownerToken = :ownerToken"));
        assertTrue(captured.conditionExpression().contains("#waveId = :waveId"));
        assertEquals("ACTIVE", captured.expressionAttributeValues().get(":nextState").s());
    }

    @Test
    void confirmedTerminalStatusKeepsSlotUntilReconciliation() {
        DynamoDbClient dynamoDb = mock(DynamoDbClient.class);
        DynamoDbRecoveryWaveStore store = new DynamoDbRecoveryWaveStore(dynamoDb, "job-state");
        RecoveryWaveLease lease = new RecoveryWaveLease(
                "job-1", "wave-1", "owner-1", 1, List.of(0),
                "b".repeat(64), "recovery-job-1-1", NOW
        );

        assertTrue(store.recordExecutionStatus(
                lease, "arn:execution:1", "SUCCEEDED", NOW.plusSeconds(30)
        ));

        ArgumentCaptor<UpdateItemRequest> request = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamoDb).updateItem(request.capture());
        assertEquals("TERMINAL",
                request.getValue().expressionAttributeValues().get(":nextState").s());
    }

    @Test
    void terminalWaveMustBeMarkedReconciledBeforeAnotherGenerationCanAcquire() {
        DynamoDbClient dynamoDb = mock(DynamoDbClient.class);
        DynamoDbRecoveryWaveStore store = new DynamoDbRecoveryWaveStore(dynamoDb, "job-state");
        RecoveryWaveSnapshot snapshot = new RecoveryWaveSnapshot(
                "job-1", "wave-1", 1, "TERMINAL", "SUCCEEDED",
                "arn:execution:1", "arn:stateMachine:1", "a".repeat(64),
                List.of(0), NOW
        );

        assertTrue(store.markReconciled(snapshot, NOW.plusSeconds(5)));

        ArgumentCaptor<UpdateItemRequest> request = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamoDb).updateItem(request.capture());
        assertTrue(request.getValue().conditionExpression().contains("#generation = :generation"));
        assertEquals("RECONCILED",
                request.getValue().expressionAttributeValues().get(":reconciled").s());
    }

    @Test
    void readsWaveSnapshotConsistently() {
        DynamoDbClient dynamoDb = mock(DynamoDbClient.class);
        when(dynamoDb.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder()
                .item(Map.ofEntries(
                        Map.entry("waveId", AttributeValue.builder().s("wave-1").build()),
                        Map.entry("generation", AttributeValue.builder().n("2").build()),
                        Map.entry("state", AttributeValue.builder().s("TERMINAL").build()),
                        Map.entry("executionStatus", AttributeValue.builder().s("SUCCEEDED").build()),
                        Map.entry("executionArn", AttributeValue.builder().s("arn:execution:1").build()),
                        Map.entry("stateMachineArn", AttributeValue.builder().s("arn:stateMachine:1").build()),
                        Map.entry("selectedShardDigest", AttributeValue.builder().s("63cbaf561c188d36f4dbafe6d8dc1bcec36aca66073c6bfd5c9787663f4efd99").build()),
                        Map.entry("selectedShardNumbers", AttributeValue.builder().l(List.of(
                                AttributeValue.builder().n("0").build(),
                                AttributeValue.builder().n("2").build())).build()),
                        Map.entry("createdAt", AttributeValue.builder().n(Long.toString(NOW.toEpochMilli())).build())
                )).build());

        RecoveryWaveSnapshot snapshot =
                new DynamoDbRecoveryWaveStore(dynamoDb, "job-state").get("job-1").orElseThrow();

        assertEquals(List.of(0, 2), snapshot.selectedShardNumbers());
        assertEquals("SUCCEEDED", snapshot.executionStatus());
        ArgumentCaptor<GetItemRequest> request = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(dynamoDb).getItem(request.capture());
        assertTrue(request.getValue().consistentRead());
    }

    @Test
    void rejectsInvalidShardSelectionsAndExecutionStatusesBeforeCallingDynamoDb() {
        DynamoDbClient dynamoDb = mock(DynamoDbClient.class);
        DynamoDbRecoveryWaveStore store = new DynamoDbRecoveryWaveStore(dynamoDb, "job-state");
        assertThrows(IllegalArgumentException.class, () -> store.acquire(
                "job-1", "wave", "owner", List.of(1, 1), "execution-1", NOW
        ));
        assertThrows(IllegalArgumentException.class, () -> store.acquire(
                "job-1", "wave", "owner", List.of(), "execution-1", NOW
        ));
        RecoveryWaveLease lease = new RecoveryWaveLease(
                "job-1", "wave", "owner", 1, List.of(1),
                "c".repeat(64), "execution-1", NOW
        );
        assertThrows(IllegalArgumentException.class, () -> store.recordExecutionStatus(
                lease, null, "CANCELLED", NOW
        ));
        verifyNoInteractions(dynamoDb);
    }
}
