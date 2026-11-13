package io.github.awsdocmigration.engine;

import io.github.awsdocmigration.domain.DocumentMigrationStatus;
import io.github.awsdocmigration.domain.DocumentRecord;
import io.github.awsdocmigration.domain.MigrationJobStatus;
import io.github.awsdocmigration.domain.MigrationShardStatus;
import io.github.awsdocmigration.domain.VersionIdentityType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShardExecutionCoordinatorTest {

    private static final String JOB_ID = "job-14";
    private static final String WORKER_ID = "worker-14";
    private static final String SHA256 =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final Instant NOW =
            Instant.parse("2026-11-13T10:00:00Z");

    @TempDir
    Path temporaryDirectory;

    private final JobStateStore jobStore = mock(JobStateStore.class);
    private final DocumentStateStore documentStore =
            mock(DocumentStateStore.class);

    private ShardExecutionCoordinator coordinator(int shardNumber) {
        when(jobStore.getJob(JOB_ID)).thenReturn(Optional.of(
                new MigrationJobState(
                        JOB_ID, MigrationJobStatus.READY, 2,
                        null, NOW.toEpochMilli(), NOW.toEpochMilli(), "a".repeat(64)
                )
        ));
        when(jobStore.transitionJob(
                eq(JOB_ID), eq(MigrationJobStatus.READY),
                eq(MigrationJobStatus.RUNNING), any(), isNull()
        )).thenReturn(true);
        when(jobStore.claimShard(
                eq(JOB_ID), eq(shardNumber), eq(WORKER_ID),
                any(), eq(Duration.ofSeconds(30))
        )).thenReturn(Optional.of(new ShardLease(
                JOB_ID, shardNumber, WORKER_ID, "lease-token-" + shardNumber,
                Instant.now().plusSeconds(30).toEpochMilli()
        )));
        when(jobStore.transitionClaimedShard(
                any(ShardLease.class),
                any(MigrationShardStatus.class),
                any(),
                any()
        )).thenReturn(true);

        return new ShardExecutionCoordinator(
                jobStore,
                documentStore,
                JOB_ID,
                shardNumber,
                WORKER_ID,
                Duration.ofSeconds(30),
                Duration.ofSeconds(3),
                3
        );
    }

    private DocumentRecord record(String documentId) {
        return new DocumentRecord(
                documentId,
                "version-1",
                documentId + ".pdf",
                "documents/" + documentId + ".pdf",
                10,
                null,
                true,
                Map.of(),
                VersionIdentityType.SOURCE_VERSION
        );
    }

    private Path manifest(DocumentRecord... records) throws Exception {
        Path path = temporaryDirectory.resolve("manifest-" + System.nanoTime() + ".jsonl");
        try (ManifestWriter writer = new ManifestWriter(path)) {
            for (DocumentRecord record : records) {
                writer.write(record);
            }
        }
        return path;
    }

    @Test
    void migratesOnlyRecordsAssignedToClaimedShardAndCompletesIt() throws Exception {
        DocumentRecord shardZeroRecord = null;
        DocumentRecord otherShardRecord = null;

        for (int index = 0; index < 100; index++) {
            DocumentRecord candidate = record("document-" + index);
            int assignedShard = ShardAssignment.shardFor(candidate, 2);
            if (assignedShard == 0 && shardZeroRecord == null) {
                shardZeroRecord = candidate;
            } else if (assignedShard == 1 && otherShardRecord == null) {
                otherShardRecord = candidate;
            }
            if (shardZeroRecord != null && otherShardRecord != null) {
                break;
            }
        }

        assertNotNull(shardZeroRecord);
        assertNotNull(otherShardRecord);

        when(documentStore.claim(
                anyString(), anyInt(), anyString(), anyString(),
                any(VersionIdentityType.class), anyString(), any(),
                any(Duration.class), anyInt()
        )).thenReturn(true);
        when(documentStore.markCompleted(
                anyString(), anyInt(), anyString(), anyString(),
                any(VersionIdentityType.class), anyString(),
                anyString(), anyString(), any()
        )).thenReturn(true);

        AtomicInteger migrationCalls = new AtomicInteger();

        ShardExecutionCoordinator.Result result = coordinator(0).execute(
                manifest(shardZeroRecord, otherShardRecord),
                ignored -> {
                    migrationCalls.incrementAndGet();
                    return new DocumentMigrationCoordinator.MigrationOutcome(
                            "destination/" + shardZeroRecord.fileName(), SHA256
                    );
                }
        );

        assertEquals(1, result.assigned());
        assertEquals(1, result.migrated());
        assertEquals(0, result.alreadyCompleted());
        assertEquals(1, migrationCalls.get());

        verify(jobStore).transitionClaimedShard(
                any(ShardLease.class),
                eq(MigrationShardStatus.COMPLETED),
                any(),
                isNull()
        );
    }

    @Test
    void countsAlreadyCompletedDocumentsWithoutMigratingAgain() throws Exception {
        DocumentRecord document = record("already-done");

        when(documentStore.claim(
                anyString(), anyInt(), anyString(), anyString(),
                any(VersionIdentityType.class), anyString(), any(),
                any(Duration.class), anyInt()
        )).thenReturn(false);
        when(documentStore.get(
                anyString(), anyInt(), anyString(), anyString(),
                any(VersionIdentityType.class)
        )).thenReturn(Optional.of(new DocumentMigrationState(
                document.documentId(),
                document.versionId(),
                DocumentMigrationStatus.COMPLETED,
                1,
                null,
                null,
                "destination/already-done.pdf",
                SHA256,
                null,
                NOW.toEpochMilli(),
                VersionIdentityType.SOURCE_VERSION
        )));

        AtomicInteger migrationCalls = new AtomicInteger();

        ShardExecutionCoordinator.Result result = coordinator(0).execute(
                manifest(document),
                ignored -> {
                    migrationCalls.incrementAndGet();
                    return new DocumentMigrationCoordinator.MigrationOutcome(
                            "destination/already-done.pdf", SHA256
                    );
                }
        );

        assertEquals(1, result.assigned());
        assertEquals(0, result.migrated());
        assertEquals(1, result.alreadyCompleted());
        assertEquals(0, migrationCalls.get());
    }

    @Test
    void failsShardWhenDocumentCannotBeClaimedAndIsNotCompleted() throws Exception {
        DocumentRecord document = record("not-claimable");

        when(documentStore.claim(
                anyString(), anyInt(), anyString(), anyString(),
                any(VersionIdentityType.class), anyString(), any(),
                any(Duration.class), anyInt()
        )).thenReturn(false);
        when(documentStore.get(
                anyString(), anyInt(), anyString(), anyString(),
                any(VersionIdentityType.class)
        )).thenReturn(Optional.empty());

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> coordinator(0).execute(
                        manifest(document),
                        ignored -> new DocumentMigrationCoordinator.MigrationOutcome(
                                "destination/not-claimable.pdf", SHA256
                        )
                )
        );

        assertTrue(failure.getMessage().contains("could not be claimed"));
        verify(jobStore).transitionClaimedShard(
                any(ShardLease.class),
                eq(MigrationShardStatus.FAILED),
                any(),
                contains("could not be claimed")
        );
    }

    @Test
    void completesAnEmptyShard() throws Exception {
        ShardExecutionCoordinator.Result result = coordinator(0).execute(
                manifest(),
                ignored -> fail("No document should be migrated")
        );

        assertEquals(0, result.assigned());
        assertEquals(0, result.migrated());
        assertEquals(0, result.alreadyCompleted());
        verify(jobStore).transitionClaimedShard(
                any(ShardLease.class),
                eq(MigrationShardStatus.COMPLETED),
                any(),
                isNull()
        );
    }
}
