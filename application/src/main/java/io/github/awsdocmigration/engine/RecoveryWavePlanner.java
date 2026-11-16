package io.github.awsdocmigration.engine;

import io.github.awsdocmigration.domain.MigrationJobStatus;
import io.github.awsdocmigration.domain.VersionIdentityType;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds a read-only recovery plan from the prepared job, immutable manifest,
 * and current durable shard state. It never acquires a wave or mutates a shard.
 */
public final class RecoveryWavePlanner {

    private final JobStateStore stateStore;

    public RecoveryWavePlanner(JobStateStore stateStore) {
        this.stateStore = Objects.requireNonNull(stateStore, "Job state store is required");
    }

    public Plan plan(
            String jobId,
            Path manifestPath,
            String sourceBucket,
            String sourcePrefix,
            Instant inspectedAt
    ) throws IOException {
        requireText(jobId, "jobId");
        requireText(sourceBucket, "sourceBucket");
        Objects.requireNonNull(manifestPath, "Manifest path is required");
        Objects.requireNonNull(sourcePrefix, "sourcePrefix must be supplied; use an empty string for the bucket root");
        Objects.requireNonNull(inspectedAt, "Inspection time is required");

        MigrationJobState job = stateStore.getJob(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Migration job was not found: " + jobId));
        if (job.status() != MigrationJobStatus.READY && job.status() != MigrationJobStatus.RUNNING) {
            throw new IllegalStateException(
                    "Recovery requires a READY or RUNNING job; current status is " + job.status());
        }
        if (job.totalShards() < 1) {
            throw new IllegalStateException("Prepared job must contain at least one shard");
        }
        if (job.manifestFingerprint() == null || job.manifestFingerprint().isBlank()) {
            throw new IllegalStateException("Prepared job has no manifest fingerprint");
        }

        String actualFingerprint = ManifestFingerprint.calculate(manifestPath);
        if (!job.manifestFingerprint().equalsIgnoreCase(actualFingerprint)) {
            throw new IllegalStateException(
                    "Manifest fingerprint does not match the prepared job; recovery was not started");
        }

        validateManifestSource(manifestPath, sourceBucket, sourcePrefix);
        List<MigrationShardState> shards = new ArrayList<>(stateStore.listShards(jobId));
        shards.sort(Comparator.comparingInt(MigrationShardState::shardNumber));
        verifyCompleteShardPlan(jobId, job.totalShards(), shards);

        List<Integer> selected = new ArrayList<>();
        Map<Integer, ShardRecoveryInspector.Disposition> assessments = new java.util.LinkedHashMap<>();
        for (MigrationShardState shard : shards) {
            ShardRecoveryInspector.Disposition disposition =
                    ShardRecoveryInspector.assess(shard, inspectedAt);
            assessments.put(shard.shardNumber(), disposition);
            if (isEligible(disposition)) {
                selected.add(shard.shardNumber());
            }
        }

        return new Plan(job, shards, selected, assessments, actualFingerprint, inspectedAt);
    }

    private static void validateManifestSource(
            Path manifestPath,
            String sourceBucket,
            String sourcePrefix
    ) throws IOException {
        try (ManifestReader reader = new ManifestReader(manifestPath)) {
            for (var record : reader) {
                String recordBucket = record.metadata().get("sourceBucket");
                if (!sourceBucket.equals(recordBucket)) {
                    throw new IllegalStateException(
                            "Manifest source bucket does not match --source-bucket for record "
                                    + record.idempotencyKey());
                }
                if (!record.relativePath().startsWith(sourcePrefix)) {
                    throw new IllegalStateException(
                            "Manifest contains a source key outside --source-prefix for record "
                                    + record.idempotencyKey());
                }
                if (record.versionIdentityType() == VersionIdentityType.LEGACY_UNSPECIFIED) {
                    throw new IllegalStateException(
                            "Manifest contains a record without an immutable S3 version or snapshot identity: "
                                    + record.idempotencyKey());
                }
            }
        }
    }

    private static void verifyCompleteShardPlan(
            String jobId,
            int expectedShards,
            List<MigrationShardState> shards
    ) {
        if (shards.size() != expectedShards) {
            throw new IllegalStateException(
                    "Job " + jobId + " has " + shards.size()
                            + " registered shards; expected " + expectedShards);
        }
        Set<Integer> seen = new HashSet<>();
        for (MigrationShardState shard : shards) {
            if (!jobId.equals(shard.jobId())
                    || shard.shardNumber() < 0
                    || shard.shardNumber() >= expectedShards
                    || !seen.add(shard.shardNumber())) {
                throw new IllegalStateException("Job has an invalid or duplicate registered shard plan");
            }
        }
        for (int shardNumber = 0; shardNumber < expectedShards; shardNumber++) {
            if (!seen.contains(shardNumber)) {
                throw new IllegalStateException("Job is missing registered shard " + shardNumber);
            }
        }
    }

    private static boolean isEligible(ShardRecoveryInspector.Disposition disposition) {
        return disposition == ShardRecoveryInspector.Disposition.READY_TO_DISPATCH
                || disposition == ShardRecoveryInspector.Disposition.RETRYABLE_FAILURE
                || disposition == ShardRecoveryInspector.Disposition.RECLAIMABLE_EXPIRED_LEASE;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    public record Plan(
            MigrationJobState job,
            List<MigrationShardState> shards,
            List<Integer> selectedShardNumbers,
            Map<Integer, ShardRecoveryInspector.Disposition> assessments,
            String manifestFingerprint,
            Instant inspectedAt
    ) {
        public Plan {
            Objects.requireNonNull(job, "Prepared job is required");
            shards = List.copyOf(shards);
            selectedShardNumbers = List.copyOf(selectedShardNumbers);
            assessments = Map.copyOf(assessments);
            Objects.requireNonNull(manifestFingerprint, "Manifest fingerprint is required");
            Objects.requireNonNull(inspectedAt, "Inspection time is required");
        }
    }
}
