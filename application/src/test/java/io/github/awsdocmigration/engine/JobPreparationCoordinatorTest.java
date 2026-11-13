package io.github.awsdocmigration.engine;

import io.github.awsdocmigration.domain.MigrationJobStatus;
import io.github.awsdocmigration.domain.MigrationShardStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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

    @Test
    void preparesJobWithManifestFingerprintAndAllowsMatchingResume() {
        InMemoryJobStateStore store = new InMemoryJobStateStore();
        JobPreparationCoordinator coordinator =
                new JobPreparationCoordinator(store);
        String fingerprint = "a".repeat(64);

        MigrationJobState first =
                coordinator.prepare("fingerprinted-job", 2, NOW, fingerprint);
        MigrationJobState resumed =
                coordinator.prepare(
                        "fingerprinted-job", 2, NOW.plusSeconds(10), fingerprint
                );

        assertEquals(MigrationJobStatus.READY, first.status());
        assertEquals(fingerprint, first.manifestFingerprint());
        assertEquals(fingerprint, resumed.manifestFingerprint());
        assertEquals(first.updatedAtEpochMillis(), resumed.updatedAtEpochMillis());
    }

    @Test
    void rejectsResumeWithDifferentManifestFingerprint() {
        InMemoryJobStateStore store = new InMemoryJobStateStore();
        JobPreparationCoordinator coordinator =
                new JobPreparationCoordinator(store);

        coordinator.prepare("fingerprinted-job", 1, NOW, "a".repeat(64));

        assertThrows(
                IllegalStateException.class,
                () -> coordinator.prepare(
                        "fingerprinted-job", 1, NOW.plusSeconds(1), "b".repeat(64)
                )
        );

        assertEquals(
                "a".repeat(64),
                store.getJob("fingerprinted-job").orElseThrow().manifestFingerprint()
        );
    }

    @Test
    void rejectsShardCountMismatchBeforeBindingFingerprint() {
        InMemoryJobStateStore store = new InMemoryJobStateStore();
        JobPreparationCoordinator coordinator =
                new JobPreparationCoordinator(store);

        coordinator.prepare("existing-job", 1, NOW);

        assertThrows(
                IllegalStateException.class,
                () -> coordinator.prepare(
                        "existing-job", 2, NOW.plusSeconds(1), "c".repeat(64)
                )
        );

        assertNull(
                store.getJob("existing-job").orElseThrow().manifestFingerprint()
        );
    }

    @Test
    void rejectsInvalidManifestFingerprintBeforeCreatingJob() {
        InMemoryJobStateStore store = new InMemoryJobStateStore();
        JobPreparationCoordinator coordinator =
                new JobPreparationCoordinator(store);

        assertThrows(
                IllegalArgumentException.class,
                () -> coordinator.prepare("invalid-fingerprint-job", 1, NOW, "invalid")
        );

        assertTrue(store.getJob("invalid-fingerprint-job").isEmpty());
    }

    private static final class InMemoryJobStateStore implements JobStateStore {

        private final Map<String, MigrationJobState> jobs = new HashMap<>();
        private final Map<String, MigrationShardState> shards = new HashMap<>();
        private final Map<String, ShardLease> leases = new HashMap<>();

        @Override
        public boolean createJob(String jobId, int totalShards, Instant now) {
            if (jobs.containsKey(jobId)) {
                return false;
            }
            long timestamp = now.toEpochMilli();
            jobs.put(jobId, new MigrationJobState(
                    jobId, MigrationJobStatus.CREATED, totalShards,
                    null, timestamp, timestamp, null
            ));
            return true;
        }

        @Override
        public Optional<MigrationJobState> getJob(String jobId) {
            return Optional.ofNullable(jobs.get(jobId));
        }

        @Override
        public boolean bindManifestFingerprint(String jobId, String fingerprint) {
            MigrationJobState current = jobs.get(jobId);
            if (current == null) {
                return false;
            }
            String canonical = fingerprint.toLowerCase(java.util.Locale.ROOT);
            if (current.manifestFingerprint() != null) {
                return current.manifestFingerprint().equals(canonical);
            }
            if (current.status() != MigrationJobStatus.CREATED
                    && current.status() != MigrationJobStatus.DISCOVERING) {
                return false;
            }
            jobs.put(jobId, new MigrationJobState(
                    current.jobId(),
                    current.status(),
                    current.totalShards(),
                    current.lastError(),
                    current.createdAtEpochMillis(),
                    current.updatedAtEpochMillis(),
                    canonical
            ));
            return true;
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
                    current.createdAtEpochMillis(), now.toEpochMilli(),
                    current.manifestFingerprint()
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
        public Optional<ShardLease> claimShard(
                String jobId,
                int shardNumber,
                String workerId,
                Instant now,
                Duration leaseDuration
        ) {
            if (jobId == null || jobId.isBlank()
                    || workerId == null || workerId.isBlank()
                    || leaseDuration == null || leaseDuration.isZero()
                    || leaseDuration.isNegative()) {
                throw new IllegalArgumentException("Valid shard claim details are required");
            }
            String key = shardKey(jobId, shardNumber);
            MigrationShardState current = shards.get(key);
            if (current == null) {
                return Optional.empty();
            }
            ShardLease existingLease = leases.get(key);
            boolean pending = current.status() == MigrationShardStatus.PENDING;
            boolean expired = current.status() == MigrationShardStatus.RUNNING
                    && (existingLease == null
                    || existingLease.expiresAtEpochMillis() <= now.toEpochMilli());
            if (!pending && !expired) {
                return Optional.empty();
            }

            long expiry = Math.addExact(
                    now.toEpochMilli(), leaseDuration.toMillis()
            );
            ShardLease lease = new ShardLease(
                    jobId, shardNumber, workerId, UUID.randomUUID().toString(), expiry
            );
            leases.put(key, lease);
            shards.put(key, new MigrationShardState(
                    jobId, shardNumber, MigrationShardStatus.RUNNING,
                    workerId, null, current.createdAtEpochMillis(), now.toEpochMilli()
            ));
            return Optional.of(lease);
        }

        @Override
        public Optional<ShardLease> renewShardLease(
                ShardLease lease,
                Instant now,
                Duration leaseDuration
        ) {
            String key = shardKey(lease.jobId(), lease.shardNumber());
            ShardLease current = leases.get(key);
            MigrationShardState shard = shards.get(key);
            if (current == null || shard == null
                    || !current.leaseToken().equals(lease.leaseToken())
                    || !current.workerId().equals(lease.workerId())
                    || current.expiresAtEpochMillis() <= now.toEpochMilli()
                    || leaseDuration == null || leaseDuration.isZero()
                    || leaseDuration.isNegative()) {
                return Optional.empty();
            }
            long expiry = Math.addExact(now.toEpochMilli(), leaseDuration.toMillis());
            ShardLease renewed = new ShardLease(
                    lease.jobId(), lease.shardNumber(), lease.workerId(),
                    lease.leaseToken(), expiry
            );
            leases.put(key, renewed);
            return Optional.of(renewed);
        }

        @Override
        public boolean transitionClaimedShard(
                ShardLease lease,
                MigrationShardStatus target,
                Instant now,
                String errorMessage
        ) {
            String key = shardKey(lease.jobId(), lease.shardNumber());
            ShardLease currentLease = leases.get(key);
            MigrationShardState current = shards.get(key);
            if (currentLease == null || current == null
                    || current.status() != MigrationShardStatus.RUNNING
                    || !currentLease.leaseToken().equals(lease.leaseToken())
                    || !currentLease.workerId().equals(lease.workerId())
                    || currentLease.expiresAtEpochMillis() <= now.toEpochMilli()
                    || (target != MigrationShardStatus.COMPLETED
                    && target != MigrationShardStatus.FAILED)) {
                return false;
            }
            leases.remove(key);
            shards.put(key, new MigrationShardState(
                    lease.jobId(), lease.shardNumber(), target, null,
                    errorMessage, current.createdAtEpochMillis(), now.toEpochMilli()
            ));
            return true;
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
