package io.github.awsdocmigration.engine;

import io.github.awsdocmigration.domain.DocumentMigrationStatus;
import io.github.awsdocmigration.domain.DocumentRecord;
import io.github.awsdocmigration.domain.VersionIdentityType;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentMigrationCoordinatorTest {

    private static final String JOB_ID = "job-1";
    private static final String WORKER_ID = "worker-1";
    private static final String SHA256 =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private final DocumentStateStore stateStore = mock(DocumentStateStore.class);

    private final DocumentMigrationCoordinator coordinator =
            new DocumentMigrationCoordinator(
                    stateStore,
                    JOB_ID,
                    0,
                    WORKER_ID,
                    Duration.ofSeconds(3),
                    3
            );

    private final DocumentRecord record = new DocumentRecord(
            "document-1",
            "source-version-1",
            "document.pdf",
            "documents/document.pdf",
            10,
            null,
            true,
            Map.of(),
            VersionIdentityType.SOURCE_VERSION
    );

    @Test
    void claimsMigratesAndMarksDocumentCompleted() throws Exception {
        when(stateStore.claim(
                eq(JOB_ID), eq(0), eq(record.documentId()), eq(record.versionId()),
                eq(VersionIdentityType.SOURCE_VERSION), eq(WORKER_ID),
                any(), eq(Duration.ofSeconds(3)), eq(3)
        )).thenReturn(true);
        when(stateStore.markCompleted(
                eq(JOB_ID), eq(0), eq(record.documentId()), eq(record.versionId()),
                eq(VersionIdentityType.SOURCE_VERSION), eq(WORKER_ID),
                eq("documents/document.pdf"), eq(SHA256), any()
        )).thenReturn(true);

        DocumentMigrationCoordinator.Result result = coordinator.execute(
                record,
                () -> new DocumentMigrationCoordinator.MigrationOutcome(
                        "documents/document.pdf", SHA256
                )
        );

        assertEquals(DocumentMigrationCoordinator.Result.COMPLETED, result);
        verify(stateStore).markCompleted(
                eq(JOB_ID), eq(0), eq(record.documentId()), eq(record.versionId()),
                eq(VersionIdentityType.SOURCE_VERSION), eq(WORKER_ID),
                eq("documents/document.pdf"), eq(SHA256), any()
        );
        verify(stateStore, never()).markFailed(
                anyString(), anyInt(), anyString(), anyString(),
                any(VersionIdentityType.class), anyString(), anyString(), any()
        );
    }

    @Test
    void skipsDocumentAlreadyMarkedCompleted() throws Exception {
        when(stateStore.claim(
                anyString(), anyInt(), anyString(), anyString(),
                any(VersionIdentityType.class), anyString(), any(),
                any(Duration.class), anyInt()
        )).thenReturn(false);

        when(stateStore.get(
                anyString(), anyInt(), anyString(), anyString(),
                any(VersionIdentityType.class)
        )).thenReturn(Optional.of(new DocumentMigrationState(
                record.documentId(),
                record.versionId(),
                DocumentMigrationStatus.COMPLETED,
                1,
                null,
                null,
                "documents/document.pdf",
                SHA256,
                null,
                System.currentTimeMillis(),
                VersionIdentityType.SOURCE_VERSION
        )));

        AtomicBoolean migrationInvoked = new AtomicBoolean();

        DocumentMigrationCoordinator.Result result = coordinator.execute(
                record,
                () -> {
                    migrationInvoked.set(true);
                    return new DocumentMigrationCoordinator.MigrationOutcome(
                            "documents/document.pdf", SHA256
                    );
                }
        );

        assertEquals(
                DocumentMigrationCoordinator.Result.ALREADY_COMPLETED,
                result
        );
        assertFalse(migrationInvoked.get());
        verify(stateStore, never()).markCompleted(
                anyString(), anyInt(), anyString(), anyString(),
                any(VersionIdentityType.class), anyString(), anyString(),
                anyString(), any()
        );
    }

    @Test
    void skipsWhenAnotherWorkerOwnsTheLease() throws Exception {
        when(stateStore.claim(
                anyString(), anyInt(), anyString(), anyString(),
                any(VersionIdentityType.class), anyString(), any(),
                any(Duration.class), anyInt()
        )).thenReturn(false);

        when(stateStore.get(
                anyString(), anyInt(), anyString(), anyString(),
                any(VersionIdentityType.class)
        )).thenReturn(Optional.empty());

        AtomicBoolean migrationInvoked = new AtomicBoolean();

        DocumentMigrationCoordinator.Result result = coordinator.execute(
                record,
                () -> {
                    migrationInvoked.set(true);
                    return new DocumentMigrationCoordinator.MigrationOutcome(
                            "documents/document.pdf", SHA256
                    );
                }
        );

        assertEquals(DocumentMigrationCoordinator.Result.NOT_CLAIMED, result);
        assertFalse(migrationInvoked.get());
    }

    @Test
    void recordsMigrationFailureAndRethrowsOriginalException() throws Exception {
        when(stateStore.claim(
                anyString(), anyInt(), anyString(), anyString(),
                any(VersionIdentityType.class), anyString(), any(),
                any(Duration.class), anyInt()
        )).thenReturn(true);

        when(stateStore.markFailed(
                anyString(), anyInt(), anyString(), anyString(),
                any(VersionIdentityType.class), anyString(), anyString(), any()
        )).thenReturn(true);

        IllegalStateException expected =
                new IllegalStateException("Simulated upload failure");

        IllegalStateException actual = assertThrows(
                IllegalStateException.class,
                () -> coordinator.execute(record, () -> {
                    throw expected;
                })
        );

        assertSame(expected, actual);
        verify(stateStore).markFailed(
                eq(JOB_ID), eq(0), eq(record.documentId()), eq(record.versionId()),
                eq(VersionIdentityType.SOURCE_VERSION), eq(WORKER_ID),
                eq("Simulated upload failure"), any()
        );
    }

    @Test
    void renewsLeaseWhileMigrationIsRunning() throws Exception {
        when(stateStore.claim(
                anyString(), anyInt(), anyString(), anyString(),
                any(VersionIdentityType.class), anyString(), any(),
                any(Duration.class), anyInt()
        )).thenReturn(true);

        when(stateStore.renewLease(
                anyString(), anyInt(), anyString(), anyString(),
                any(VersionIdentityType.class), anyString(), any(),
                any(Duration.class)
        )).thenReturn(true);

        when(stateStore.markCompleted(
                anyString(), anyInt(), anyString(), anyString(),
                any(VersionIdentityType.class), anyString(), anyString(),
                anyString(), any()
        )).thenReturn(true);

        DocumentMigrationCoordinator.Result result = coordinator.execute(
                record,
                () -> {
                    Thread.sleep(1_200);
                    return new DocumentMigrationCoordinator.MigrationOutcome(
                            "documents/document.pdf", SHA256
                    );
                }
        );

        assertEquals(DocumentMigrationCoordinator.Result.COMPLETED, result);
        verify(stateStore, atLeastOnce()).renewLease(
                eq(JOB_ID), eq(0), eq(record.documentId()), eq(record.versionId()),
                eq(VersionIdentityType.SOURCE_VERSION), eq(WORKER_ID),
                any(), eq(Duration.ofSeconds(3))
        );
    }

    @Test
    void refusesCompletionAfterLeaseHasBeenLost() throws Exception {
        when(stateStore.claim(
                anyString(), anyInt(), anyString(), anyString(),
                any(VersionIdentityType.class), anyString(), any(),
                any(Duration.class), anyInt()
        )).thenReturn(true);

        when(stateStore.renewLease(
                anyString(), anyInt(), anyString(), anyString(),
                any(VersionIdentityType.class), anyString(), any(),
                any(Duration.class)
        )).thenReturn(false);

        when(stateStore.markFailed(
                anyString(), anyInt(), anyString(), anyString(),
                any(VersionIdentityType.class), anyString(), anyString(), any()
        )).thenReturn(false);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> coordinator.execute(record, () -> {
                    Thread.sleep(1_200);
                    return new DocumentMigrationCoordinator.MigrationOutcome(
                            "documents/document.pdf", SHA256
                    );
                })
        );

        assertTrue(failure.getMessage().contains("lease was lost"));
        verify(stateStore, never()).markCompleted(
                anyString(), anyInt(), anyString(), anyString(),
                any(VersionIdentityType.class), anyString(), anyString(),
                anyString(), any()
        );
    }
}
