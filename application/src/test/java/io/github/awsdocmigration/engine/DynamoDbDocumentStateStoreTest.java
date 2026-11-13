package io.github.awsdocmigration.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DynamoDbDocumentStateStoreTest {

    private DynamoDbClient dynamoDb;
    private DynamoDbDocumentStateStore store;
    private final Instant now = Instant.parse("2026-11-13T10:00:00Z");

    @BeforeEach
    void setUp() {
        dynamoDb = mock(DynamoDbClient.class);
        store = new DynamoDbDocumentStateStore(
                dynamoDb,
                "migration-state"
        );
    }

    @Test
    void claimsDocumentWithConditionalLeaseAndIncrementsAttemptCount()
            throws Exception {

        when(dynamoDb.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(UpdateItemResponse.builder().build());

        assertTrue(store.claim(
                "job-1",
                3,
                "document-1",
                "version-1",
                "worker-a",
                now,
                Duration.ofMinutes(5),
                4
        ));

        ArgumentCaptor<UpdateItemRequest> captor =
                ArgumentCaptor.forClass(UpdateItemRequest.class);

        verify(dynamoDb).updateItem(captor.capture());

        UpdateItemRequest request = captor.getValue();

        assertEquals("migration-state", request.tableName());
        assertTrue(request.conditionExpression().contains("#leaseExpiresAt <= :now"));
        assertTrue(request.conditionExpression().contains("#attemptCount < :maxAttempts"));
        assertTrue(request.updateExpression().contains("if_not_exists(#attemptCount, :zero) + :one"));
        assertEquals(
                "JOB#job-1#SHARD#3",
                request.key().get("pk").s()
        );
        assertTrue(request.key().get("sk").s().startsWith("DOC#"));
        assertEquals(
                now.plus(Duration.ofMinutes(5)).toEpochMilli(),
                Long.parseLong(request.expressionAttributeValues().get(":leaseExpiry").n())
        );
    }

    @Test
    void returnsFalseWhenAnotherWorkerOwnsTheLease() {

        when(dynamoDb.updateItem(any(UpdateItemRequest.class)))
                .thenThrow(ConditionalCheckFailedException.builder()
                        .message("Conditional request failed")
                        .build());

        assertFalse(store.claim(
                "job-1",
                0,
                "document-1",
                "version-1",
                "worker-b",
                now,
                Duration.ofMinutes(5),
                4
        ));
    }

    @Test
    void marksDocumentCompletedOnlyForCurrentLeaseOwner() {

        when(dynamoDb.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(UpdateItemResponse.builder().build());

        String checksum = "a".repeat(64);

        assertTrue(store.markCompleted(
                "job-1",
                0,
                "document-1",
                "version-1",
                "worker-a",
                "documents/document-1.pdf",
                checksum,
                now
        ));

        ArgumentCaptor<UpdateItemRequest> captor =
                ArgumentCaptor.forClass(UpdateItemRequest.class);

        verify(dynamoDb).updateItem(captor.capture());

        UpdateItemRequest request = captor.getValue();

        assertTrue(request.conditionExpression().contains("#leaseOwner = :worker"));
        assertTrue(request.conditionExpression().contains("#leaseExpiresAt > :now"));
        assertEquals(
                "COMPLETED",
                request.expressionAttributeValues().get(":completed").s()
        );
        assertEquals(
                checksum,
                request.expressionAttributeValues().get(":checksum").s()
        );
        assertTrue(request.updateExpression().contains("REMOVE #leaseOwner, #leaseExpiresAt"));
    }

    @Test
    void refusesCompletionWhenLeaseHasExpiredOrBeenReclaimed() {

        when(dynamoDb.updateItem(any(UpdateItemRequest.class)))
                .thenThrow(ConditionalCheckFailedException.builder()
                        .message("Lease no longer belongs to worker")
                        .build());

        assertFalse(store.markCompleted(
                "job-1",
                0,
                "document-1",
                "version-1",
                "old-worker",
                "documents/document-1.pdf",
                "b".repeat(64),
                now
        ));
    }

    @Test
    void marksDocumentFailedAndBoundsStoredErrorLength() {

        when(dynamoDb.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(UpdateItemResponse.builder().build());

        assertTrue(store.markFailed(
                "job-1",
                0,
                "document-1",
                "version-1",
                "worker-a",
                "x".repeat(3_000),
                now
        ));

        ArgumentCaptor<UpdateItemRequest> captor =
                ArgumentCaptor.forClass(UpdateItemRequest.class);

        verify(dynamoDb).updateItem(captor.capture());

        assertEquals(
                2_000,
                captor.getValue().expressionAttributeValues().get(":error").s().length()
        );
        assertEquals(
                "FAILED",
                captor.getValue().expressionAttributeValues().get(":failed").s()
        );
    }

    @Test
    void readsPersistedDocumentStateConsistently() {

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("documentId", AttributeValue.builder().s("document-1").build());
        item.put("versionId", AttributeValue.builder().s("version-1").build());
        item.put("status", AttributeValue.builder().s("COMPLETED").build());
        item.put("attemptCount", AttributeValue.builder().n("2").build());
        item.put("destinationKey", AttributeValue.builder()
                .s("documents/document-1.pdf").build());
        item.put("checksum", AttributeValue.builder().s("c".repeat(64)).build());
        item.put("updatedAt", AttributeValue.builder().n("1794564000000").build());

        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(item).build());

        Optional<DocumentMigrationState> result = store.get(
                "job-1",
                0,
                "document-1",
                "version-1"
        );

        assertTrue(result.isPresent());
        assertEquals(io.github.awsdocmigration.domain.DocumentMigrationStatus.COMPLETED, result.get().status());
        assertEquals(2, result.get().attemptCount());
        assertEquals("documents/document-1.pdf", result.get().destinationKey());
        assertTrue(result.get().leaseOwner() == null);

        ArgumentCaptor<GetItemRequest> captor =
                ArgumentCaptor.forClass(GetItemRequest.class);

        verify(dynamoDb).getItem(captor.capture());
        assertTrue(captor.getValue().consistentRead());
    }

    @Test
    void returnsEmptyWhenStateDoesNotExist() {

        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().build());

        assertEquals(
                Optional.empty(),
                store.get("job-1", 0, "document-1", "version-1")
        );
    }

    @Test
    void validatesLeaseAndAttemptConfigurationBeforeCallingAws() {

        assertThrows(
                IllegalArgumentException.class,
                () -> store.claim(
                        "job-1", 0, "document-1", "version-1",
                        "worker-a", now, Duration.ZERO, 4
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> store.claim(
                        "job-1", 0, "document-1", "version-1",
                        "worker-a", now, Duration.ofMinutes(1), 0
                )
        );

        verifyNoInteractions(dynamoDb);
    }
}
