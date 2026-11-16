package io.github.awsdocmigration.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.awsdocmigration.domain.MigrationJobStatus;
import io.github.awsdocmigration.domain.MigrationShardStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecoveryWavePlannerTest {

    private static final Instant NOW = Instant.parse("2026-11-16T03:00:00Z");
    private static final String JOB_ID = "job-operator-recovery";
    private static final String SOURCE_BUCKET = "source-bucket";
    private static final String SOURCE_PREFIX = "exports/";
    private static final String RECORD = "{\"documentId\":\"doc-1\",\"versionId\":\"v1\","
            + "\"fileName\":\"doc-1.pdf\",\"relativePath\":\"exports/doc-1.pdf\","
            + "\"sizeBytes\":1,\"expectedSha256\":null,\"isLatest\":true,"
            + "\"metadata\":{\"sourceBucket\":\"source-bucket\"},"
            + "\"versionIdentityType\":\"SOURCE_VERSION\"}";

    @TempDir
    Path tempDir;

    private JobStateStore store;
    private Path manifest;

    @BeforeEach
    void setUp() throws Exception {
        store = mock(JobStateStore.class);
        manifest = tempDir.resolve("manifest.jsonl");
        Files.writeString(manifest, RECORD + System.lineSeparator());
        when(store.getJob(JOB_ID)).thenReturn(Optional.of(new MigrationJobState(
                JOB_ID, MigrationJobStatus.RUNNING, 7, null, 1L, 2L,
                ManifestFingerprint.calculate(manifest))));
    }

    @Test
    void selectsOnlyPendingRetryableAndExpiredLeaseShards() throws Exception {
        when(store.listShards(JOB_ID)).thenReturn(List.of(
                shard(0, MigrationShardStatus.PENDING, 0, null),
                shard(1, MigrationShardStatus.FAILED, 1, null),
                shard(2, MigrationShardStatus.RUNNING, 1, NOW.minusSeconds(1).toEpochMilli()),
                shard(3, MigrationShardStatus.RUNNING, 1, NOW.plusSeconds(60).toEpochMilli()),
                shard(4, MigrationShardStatus.RUNNING, 1, null),
                shard(5, MigrationShardStatus.COMPLETED, 1, null),
                shard(6, MigrationShardStatus.FAILED, JobStateStore.MAX_SHARD_ATTEMPTS, null)));

        RecoveryWavePlanner.Plan plan = new RecoveryWavePlanner(store)
                .plan(JOB_ID, manifest, SOURCE_BUCKET, SOURCE_PREFIX, NOW);

        assertEquals(List.of(0, 1, 2), plan.selectedShardNumbers());
        assertEquals(7, plan.shards().size());
        assertEquals(ShardRecoveryInspector.Disposition.WAIT_FOR_ACTIVE_LEASE, plan.assessments().get(3));
        assertEquals(ShardRecoveryInspector.Disposition.LEASE_EXPIRY_UNKNOWN, plan.assessments().get(4));
        assertEquals(ShardRecoveryInspector.Disposition.COMPLETED, plan.assessments().get(5));
        assertEquals(ShardRecoveryInspector.Disposition.ATTEMPTS_EXHAUSTED, plan.assessments().get(6));
        verify(store).getJob(JOB_ID);
        verify(store).listShards(JOB_ID);
        verifyNoMoreInteractions(store);
    }

    @Test
    void rejectsManifestFingerprintMismatch() throws Exception {
        when(store.getJob(JOB_ID)).thenReturn(Optional.of(new MigrationJobState(
                JOB_ID, MigrationJobStatus.READY, 1, null, 1L, 2L, "0".repeat(64))));

        assertThrows(IllegalStateException.class, () -> new RecoveryWavePlanner(store)
                .plan(JOB_ID, manifest, SOURCE_BUCKET, SOURCE_PREFIX, NOW));

        verify(store, never()).listShards(JOB_ID);
    }

    @Test
    void rejectsIncompleteOrNonContiguousRegisteredShardPlan() throws Exception {
        when(store.listShards(JOB_ID)).thenReturn(List.of(
                shard(0, MigrationShardStatus.PENDING, 0, null),
                shard(2, MigrationShardStatus.PENDING, 0, null),
                shard(3, MigrationShardStatus.PENDING, 0, null),
                shard(4, MigrationShardStatus.PENDING, 0, null),
                shard(5, MigrationShardStatus.PENDING, 0, null),
                shard(6, MigrationShardStatus.PENDING, 0, null),
                shard(7, MigrationShardStatus.PENDING, 0, null)));

        assertThrows(IllegalStateException.class, () -> new RecoveryWavePlanner(store)
                .plan(JOB_ID, manifest, SOURCE_BUCKET, SOURCE_PREFIX, NOW));
    }

    @Test
    void rejectsManifestFromDifferentSourceBucket() throws Exception {
        Files.writeString(manifest, RECORD.replace("source-bucket", "another-bucket"));
        when(store.getJob(JOB_ID)).thenReturn(Optional.of(new MigrationJobState(
                JOB_ID, MigrationJobStatus.READY, 7, null, 1L, 2L,
                ManifestFingerprint.calculate(manifest))));

        assertThrows(IllegalStateException.class, () -> new RecoveryWavePlanner(store)
                .plan(JOB_ID, manifest, SOURCE_BUCKET, SOURCE_PREFIX, NOW));
    }

    @Test
    void rejectsManifestOutsideExplicitSourcePrefix() throws Exception {
        Files.writeString(manifest, RECORD.replace("exports/", "other/"));
        when(store.getJob(JOB_ID)).thenReturn(Optional.of(new MigrationJobState(
                JOB_ID, MigrationJobStatus.READY, 7, null, 1L, 2L,
                ManifestFingerprint.calculate(manifest))));

        assertThrows(IllegalStateException.class, () -> new RecoveryWavePlanner(store)
                .plan(JOB_ID, manifest, SOURCE_BUCKET, SOURCE_PREFIX, NOW));
    }

    @Test
    void rejectsTerminalJob() throws Exception {
        when(store.getJob(JOB_ID)).thenReturn(Optional.of(new MigrationJobState(
                JOB_ID, MigrationJobStatus.COMPLETED, 7, null, 1L, 2L,
                ManifestFingerprint.calculate(manifest))));

        assertThrows(IllegalStateException.class, () -> new RecoveryWavePlanner(store)
                .plan(JOB_ID, manifest, SOURCE_BUCKET, SOURCE_PREFIX, NOW));
    }

    private static MigrationShardState shard(
            int number, MigrationShardStatus status, int attempts, Long leaseExpiry) {
        return new MigrationShardState(
                JOB_ID, number, status, null, null, attempts, 1L, 2L, leaseExpiry);
    }
}
