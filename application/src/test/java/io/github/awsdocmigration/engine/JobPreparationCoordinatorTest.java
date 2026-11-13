package io.github.awsdocmigration.engine;

import io.github.awsdocmigration.domain.MigrationJobStatus;
import io.github.awsdocmigration.domain.MigrationShardStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class JobPreparationCoordinatorTest {

    private static final Instant NOW =
            Instant.parse("2026-11-13T10:00:00Z");

    @Test
    void createsJobRegistersEveryShardAndMarksJobReady() {
        InMemoryJobStateStore store = new InMemoryJobStateStore();

        MigrationJobState result =
                new JobPreparationCoordinator(store).prepare("job-1", 3, NOW);

        assertEquals(MigrationJobStatus.READY, result.status());
        assertEquals(3, result.totalShards());
        assertEquals(List.of(0, 1, 2), store.listShards("job-1").stream()
                .map(MigrationShardState::shardNumber)
                .toList());
        assertTrue(store.listShards("job-1").stream()
                .allMatch(shard -> shard.status() == MigrationShardStatus.PENDING));
    }

    @Test
    void resumesPartiallyRegisteredJobWithoutDuplicatingShards() {
        InMemoryJobStateStore store = new InMemoryJobStateStore();
        assertTrue(store.createJob("job-2", 3, NOW));
        assertTrue(store.transitionJob("job-2", MigrationJobStatus.CREATED,
                MigrationJobStatus.DISCOVERING, NOW, null));
        assertTrue(store.createShard("job-2", 0, NOW));

        MigrationJobState result =
                new JobPreparationCoordinator(store).prepare("job-2", 3, NOW);

        assertEquals(MigrationJobStatus.READY, result.status());
        assertEquals(List.of(0, 1, 2), store.listShards("job-2").stream()
                .map(MigrationShardState::shardNumber)
                .toList());
    }

    @Test
    void repeatedPreparationOfReadyJobDoesNotChangeItsState() {
        InMemoryJobStateStore store = new InMemoryJobStateStore();
        JobPreparationCoordinator coordinator =
                new JobPreparationCoordinator(store);

        MigrationJobState first = coordinator.prepare("job-3", 2, NOW);
        MigrationJobState second =
                coordinator.prepare("job-3", 2, NOW.plusSeconds(10));

        assertEquals(MigrationJobStatus.READY, second.status());
        assertEquals(first.createdAtEpochMillis(),
                second.createdAtEpochMillis());
        assertEquals(first.updatedAtEpochMillis(),
                second.updatedAtEpochMillis());
        assertEquals(2, store.listShards("job-3").size());
    }

    @Test
    void supportsAJobWithNoShards() {
        InMemoryJobStateStore store = new InMemoryJobStateStore();

        MigrationJobState result =
                new JobPreparationCoordinator(store).prepare("empty-job", 0, NOW);

        assertEquals(MigrationJobStatus.READY, result.status());
        assertTrue(store.listShards("empty-job").isEmpty());
    }

    @Test
    void rejectsDifferentShardCountForAnExistingJob() {
        InMemoryJobStateStore store = new InMemoryJobStateStore();
        JobPreparationCoordinator coordinator =
                new JobPreparationCoordinator(store);

        coordinator.prepare("job-4", 2, NOW);

        assertThrows(
                IllegalStateException.class,
                () -> coordinator.prepare("job-4", 3, NOW)
        );
    }

    @Test
    void rejectsAnExistingNonPendingShardDuringIncompletePreparation() {
        InMemoryJobStateStore store = new InMemoryJobStateStore();
        assertTrue(store.createJob("job-5", 2, NOW));
        assertTrue(store.transitionJob("job-5", MigrationJobStatus.CREATED,
                MigrationJobStatus.DISCOVERING, NOW, null));
        assertTrue(store.createShard("job-5", 0, NOW));
        assertTrue(store.transitionShard("job-5", 0,
                MigrationShardStatus.PENDING, MigrationShardStatus.RUNNING,
                "worker-1", NOW, null));

        assertThrows(
                IllegalStateException.class,
                () -> new JobPreparationCoordinator(store).prepare(
                        "job-5", 2, NOW
                )
        );
    }

    private static final class InMemoryJobStateStore implements JobStateStore {

        private final Map<String, MigrationJobState> jobs = new HashMap<>();
        private final Map<String, MigrationShardState> shards = new HashMap<>();

        @Override
        public boolean createJob(String jobId, int totalShards, Instant now) {
            if (jobs.containsKey(jobId)) {
                return false;
            }
            long timestamp = now.toEpochMilli();
            jobs.put(jobId, new MigrationJobState(
                    jobId, MigrationJobStatus.CREATED, totalShards,
                    null, timestamp, timestamp
            ));
            return true;
        }

        @Override
        public Optional<MigrationJobState> getJob(String jobId) {
            return Optional.ofNullable(jobs.get(jobId));
        }

        @Override
        public boolean transitionJob(
                String jobId,
                MigrationJobStatus expected,
                MigrationJobStatus target,
                Instant now,
                String errorMessage
        ) {
            MigrationJobState current = jobs.get(jobId);
            if (current == null || current.status() != expected
                    || !expected.canTransitionTo(target)) {
                return false;
            }
            if (target == MigrationJobStatus.READY
                    && shardsFor(jobId).size() != current.totalShards()) {
                return false;
            }
            jobs.put(jobId, new MigrationJobState(
                    jobId, target, current.totalShards(), errorMessage,
                    current.createdAtEpochMillis(), now.toEpochMilli()
            ));
            return true;
        }

        @Override
        public boolean createShard(String jobId, int shardNumber, Instant now) {
            MigrationJobState job = jobs.get(jobId);
            String key = shardKey(jobId, shardNumber);
            if (job == null || job.status() != MigrationJobStatus.DISCOVERING
                    || shardNumber < 0 || shardNumber >= job.totalShards()
                    || shards.containsKey(key)) {
                return false;
            }
            long timestamp = now.toEpochMilli();
            shards.put(key, new MigrationShardState(
                    jobId, shardNumber, MigrationShardStatus.PENDING,
                    null, null, timestamp, timestamp
            ));
            return true;
        }

        @Override
        public Optional<MigrationShardState> getShard(
                String jobId,
                int shardNumber
        ) {
            return Optional.ofNullable(shards.get(shardKey(jobId, shardNumber)));
        }

        @Override
        public List<MigrationShardState> listShards(String jobId) {
            return shardsFor(jobId);
        }

        private List<MigrationShardState> shardsFor(String jobId) {
            return shards.values().stream()
                    .filter(shard -> shard.jobId().equals(jobId))
                    .sorted(Comparator.comparingInt(
                            MigrationShardState::shardNumber
                    ))
                    .toList();
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
            String key = shardKey(jobId, shardNumber);
            MigrationShardState current = shards.get(key);
            if (current == null || current.status() != expected
                    || !expected.canTransitionTo(target)) {
                return false;
            }
            if (target == MigrationShardStatus.RUNNING
                    && (workerId == null || workerId.isBlank())) {
                return false;
            }
            shards.put(key, new MigrationShardState(
                    jobId,
                    shardNumber,
                    target,
                    target == MigrationShardStatus.RUNNING ? workerId : null,
                    errorMessage,
                    current.createdAtEpochMillis(),
                    now.toEpochMilli()
            ));
            return true;
        }

        private static String shardKey(String jobId, int shardNumber) {
            return jobId + "#" + shardNumber;
        }
    }
}
