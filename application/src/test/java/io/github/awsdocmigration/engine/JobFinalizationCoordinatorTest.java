package io.github.awsdocmigration.engine;

import io.github.awsdocmigration.domain.MigrationJobStatus;
import io.github.awsdocmigration.domain.MigrationShardStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JobFinalizationCoordinatorTest {

    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");

    @Test
    void completesJobWhenEveryShardCompleted() {
        JobStateStore store = runningJobStore(2);
        when(store.listShards("job-1")).thenReturn(List.of(
                shard(0, MigrationShardStatus.COMPLETED, 1, null),
                shard(1, MigrationShardStatus.COMPLETED, 2, null)
        ));
        when(store.transitionJob("job-1", MigrationJobStatus.RUNNING,
                MigrationJobStatus.COMPLETED, NOW, null)).thenReturn(true);
        when(store.getJob("job-1")).thenReturn(
                Optional.of(job(MigrationJobStatus.RUNNING, 2)),
                Optional.of(job(MigrationJobStatus.COMPLETED, 2))
        );

        Optional<MigrationJobState> result =
                new JobFinalizationCoordinator(store).finalizeIfReady("job-1", NOW);

        assertEquals(MigrationJobStatus.COMPLETED, result.orElseThrow().status());
        verify(store).transitionJob("job-1", MigrationJobStatus.RUNNING,
                MigrationJobStatus.COMPLETED, NOW, null);
    }

    @Test
    void doesNotFinalizeWhileAnyShardIsPendingOrRunning() {
        for (MigrationShardStatus blocked : List.of(
                MigrationShardStatus.PENDING, MigrationShardStatus.RUNNING)) {
            JobStateStore store = runningJobStore(1);
            when(store.listShards("job-1")).thenReturn(List.of(
                    shard(0, blocked, 1, null)
            ));

            assertTrue(new JobFinalizationCoordinator(store)
                    .finalizeIfReady("job-1", NOW).isEmpty());
            verify(store, never()).transitionJob(
                    anyString(), any(), any(), any(), any()
            );
        }
    }

    @Test
    void doesNotFinalizeWhileFailedShardCanStillBeRetried() {
        JobStateStore store = runningJobStore(1);
        when(store.listShards("job-1")).thenReturn(List.of(
                shard(0, MigrationShardStatus.FAILED, 2, "temporary failure")
        ));

        assertTrue(new JobFinalizationCoordinator(store)
                .finalizeIfReady("job-1", NOW).isEmpty());
        verify(store, never()).transitionJob(
                anyString(), any(), any(), any(), any()
        );
    }

    @Test
    void finalizesMixedOutcomesWithAggregatedShardFailures() {
        JobStateStore store = runningJobStore(3);
        when(store.listShards("job-1")).thenReturn(List.of(
                shard(0, MigrationShardStatus.COMPLETED, 1, null),
                shard(1, MigrationShardStatus.FAILED, 3, "source object missing"),
                shard(2, MigrationShardStatus.FAILED, 3, "checksum mismatch")
        ));
        String errors = "Shard 1: source object missing; Shard 2: checksum mismatch";
        when(store.transitionJob("job-1", MigrationJobStatus.RUNNING,
                MigrationJobStatus.COMPLETED_WITH_ERRORS, NOW, errors)).thenReturn(true);
        when(store.getJob("job-1")).thenReturn(
                Optional.of(job(MigrationJobStatus.RUNNING, 3)),
                Optional.of(new MigrationJobState("job-1",
                        MigrationJobStatus.COMPLETED_WITH_ERRORS, 3, errors,
                        1L, NOW.toEpochMilli(), null))
        );

        MigrationJobState result = new JobFinalizationCoordinator(store)
                .finalizeIfReady("job-1", NOW).orElseThrow();

        assertEquals(MigrationJobStatus.COMPLETED_WITH_ERRORS, result.status());
        verify(store).transitionJob("job-1", MigrationJobStatus.RUNNING,
                MigrationJobStatus.COMPLETED_WITH_ERRORS, NOW, errors);
    }

    @Test
    void doesNotFinalizeWhenShardListIsIncompleteOrNonContiguous() {
        JobStateStore store = runningJobStore(2);
        when(store.listShards("job-1")).thenReturn(List.of(
                shard(0, MigrationShardStatus.COMPLETED, 1, null)
        ));

        assertTrue(new JobFinalizationCoordinator(store)
                .finalizeIfReady("job-1", NOW).isEmpty());

        when(store.listShards("job-1")).thenReturn(List.of(
                shard(0, MigrationShardStatus.COMPLETED, 1, null),
                shard(2, MigrationShardStatus.COMPLETED, 1, null)
        ));

        assertTrue(new JobFinalizationCoordinator(store)
                .finalizeIfReady("job-1", NOW).isEmpty());
        verify(store, never()).transitionJob(
                anyString(), any(), any(), any(), any()
        );
    }

    @Test
    void repeatedFinalizationReturnsExistingTerminalStateWithoutAnotherTransition() {
        JobStateStore store = runningJobStore(1);
        when(store.listShards("job-1")).thenReturn(List.of(
                shard(0, MigrationShardStatus.COMPLETED, 1, null)
        ));
        MigrationJobState completed = job(MigrationJobStatus.COMPLETED, 1);
        when(store.transitionJob("job-1", MigrationJobStatus.RUNNING,
                MigrationJobStatus.COMPLETED, NOW, null)).thenReturn(false);
        when(store.getJob("job-1")).thenReturn(
                Optional.of(job(MigrationJobStatus.RUNNING, 1)),
                Optional.of(completed),
                Optional.of(completed)
        );

        JobFinalizationCoordinator coordinator =
                new JobFinalizationCoordinator(store);

        assertEquals(MigrationJobStatus.COMPLETED,
                coordinator.finalizeIfReady("job-1", NOW).orElseThrow().status());
        assertEquals(MigrationJobStatus.COMPLETED,
                coordinator.finalizeIfReady("job-1", NOW).orElseThrow().status());

        verify(store, times(1)).transitionJob("job-1", MigrationJobStatus.RUNNING,
                MigrationJobStatus.COMPLETED, NOW, null);
    }

    private static JobStateStore runningJobStore(int totalShards) {
        JobStateStore store = mock(JobStateStore.class);
        when(store.getJob("job-1")).thenReturn(
                Optional.of(job(MigrationJobStatus.RUNNING, totalShards))
        );
        return store;
    }

    private static MigrationJobState job(MigrationJobStatus status, int totalShards) {
        return new MigrationJobState(
                "job-1", status, totalShards, null, 1L, NOW.toEpochMilli(), null
        );
    }

    private static MigrationShardState shard(
            int number, MigrationShardStatus status, int attempts, String error
    ) {
        return new MigrationShardState(
                "job-1", number, status, null, error, attempts, 1L, NOW.toEpochMilli()
        );
    }
}
