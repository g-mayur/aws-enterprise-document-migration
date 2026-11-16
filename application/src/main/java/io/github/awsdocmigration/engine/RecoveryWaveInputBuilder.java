package io.github.awsdocmigration.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.awsdocmigration.domain.VersionPolicy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** Builds the exact input contract consumed by infrastructure/orchestration.tf. */
public final class RecoveryWaveInputBuilder {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_STEP_FUNCTIONS_INPUT_BYTES = 256 * 1024;

    private RecoveryWaveInputBuilder() {
    }

    public static String build(
            String jobId,
            String sourceBucket,
            String sourcePrefix,
            String destinationBucket,
            String manifestS3Uri,
            int totalShards,
            List<Integer> selectedShardNumbers,
            String documentStateTable,
            String jobStateTable,
            VersionPolicy versionPolicy,
            String region,
            int leaseSeconds,
            int maximumDocumentAttempts,
            String waveId
    ) {
        requireText(jobId, "jobId");
        requireText(sourceBucket, "sourceBucket");
        Objects.requireNonNull(sourcePrefix, "sourcePrefix is required");
        requireText(destinationBucket, "destinationBucket");
        requireText(manifestS3Uri, "manifestS3Uri");
        requireText(documentStateTable, "documentStateTable");
        requireText(jobStateTable, "jobStateTable");
        Objects.requireNonNull(versionPolicy, "versionPolicy is required");
        requireText(region, "region");
        requireText(waveId, "waveId");
        if (totalShards < 1 || leaseSeconds < 3 || maximumDocumentAttempts < 1) {
            throw new IllegalArgumentException("Shard count, lease duration, and document attempts must be positive");
        }
        List<Integer> selected = List.copyOf(selectedShardNumbers);
        if (selected.isEmpty()
                || selected.stream().anyMatch(number -> number == null || number < 0 || number >= totalShards)
                || selected.stream().distinct().count() != selected.size()) {
            throw new IllegalArgumentException("Selected shard numbers must be unique and within the prepared shard range");
        }

        ObjectNode root = JSON.createObjectNode();
        root.put("jobId", jobId);
        root.put("sourceBucket", sourceBucket);
        root.put("sourcePrefix", sourcePrefix);
        root.put("destinationBucket", destinationBucket);
        root.put("manifestS3Uri", manifestS3Uri);
        root.put("totalShards", totalShards);
        ArrayNode shards = root.putArray("shards");

        for (int shardNumber : selected) {
            ObjectNode shard = shards.addObject();
            shard.put("shardNumber", shardNumber);
            ArrayNode command = shard.putArray("command");
            add(command, "--source-type", "s3");
            add(command, "--source-bucket", sourceBucket);
            add(command, "--source-prefix", sourcePrefix);
            add(command, "--destination-bucket", destinationBucket);
            add(command, "--manifest-s3-uri", manifestS3Uri);
            add(command, "--version-policy", versionPolicy.name());
            add(command, "--state-table", documentStateTable);
            add(command, "--job-state-table", jobStateTable);
            add(command, "--job-id", jobId);
            add(command, "--total-shards", Integer.toString(totalShards));
            add(command, "--shard-number", Integer.toString(shardNumber));
            add(command, "--worker-id", "recovery-" + waveId + "-shard-" + shardNumber);
            add(command, "--lease-seconds", Integer.toString(leaseSeconds));
            add(command, "--max-attempts", Integer.toString(maximumDocumentAttempts));
            add(command, "--region", region);
        }

        try {
            String input = JSON.writeValueAsString(root);
            if (input.getBytes(StandardCharsets.UTF_8).length > MAX_STEP_FUNCTIONS_INPUT_BYTES) {
                throw new IllegalArgumentException(
                        "Recovery input exceeds the Step Functions 256 KiB limit; reduce the selected shard set.");
            }
            return input;
        } catch (JsonProcessingException impossibleForJsonNodes) {
            throw new IllegalStateException("Unable to serialize recovery execution input", impossibleForJsonNodes);
        }
    }

    private static void add(ArrayNode command, String name, String value) {
        command.add(name);
        command.add(value);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
