package io.github.awsdocmigration.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.awsdocmigration.domain.VersionPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryWaveInputBuilderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void buildsStateMachineInputWithExactSelectedShardsAndWorkerCommand() throws Exception {
        String input = RecoveryWaveInputBuilder.build(
                "job-1", "source-bucket", "exports/", "destination-bucket",
                "s3://staging-bucket/manifests/job-1.jsonl", 4, List.of(1, 3),
                "document-state", "job-state", VersionPolicy.ALL_VERSIONS,
                "us-east-1", 300, 5, "wave-123");

        JsonNode root = JSON.readTree(input);
        assertEquals("job-1", root.path("jobId").asText());
        assertEquals("source-bucket", root.path("sourceBucket").asText());
        assertEquals("exports/", root.path("sourcePrefix").asText());
        assertEquals("destination-bucket", root.path("destinationBucket").asText());
        assertEquals("s3://staging-bucket/manifests/job-1.jsonl", root.path("manifestS3Uri").asText());
        assertEquals(4, root.path("totalShards").asInt());
        assertEquals(2, root.path("shards").size());
        assertEquals(1, root.path("shards").get(0).path("shardNumber").asInt());
        assertEquals(3, root.path("shards").get(1).path("shardNumber").asInt());

        List<String> command = JSON.convertValue(
                root.path("shards").get(0).path("command"),
                JSON.getTypeFactory().constructCollectionType(List.class, String.class));
        assertTrue(command.containsAll(List.of(
                "--source-type", "s3",
                "--source-bucket", "source-bucket",
                "--source-prefix", "exports/",
                "--destination-bucket", "destination-bucket",
                "--manifest-s3-uri", "s3://staging-bucket/manifests/job-1.jsonl",
                "--version-policy", "ALL_VERSIONS",
                "--state-table", "document-state",
                "--job-state-table", "job-state",
                "--job-id", "job-1",
                "--total-shards", "4",
                "--shard-number", "1",
                "--worker-id", "recovery-wave-123-shard-1",
                "--lease-seconds", "300",
                "--max-attempts", "5",
                "--region", "us-east-1")));
    }

    @Test
    void rejectsInputThatExceedsStepFunctionsPayloadLimit() {
        List<Integer> selected = java.util.stream.IntStream.range(0, 1000).boxed().toList();
        assertThrows(IllegalArgumentException.class, () -> RecoveryWaveInputBuilder.build(
                "job-1", "source", "", "destination", "s3://staging/manifest", 1000,
                selected, "documents", "jobs", VersionPolicy.ALL_VERSIONS,
                "us-east-1", 300, 5, "wave"));
    }

    @Test
    void rejectsDuplicateOrOutOfRangeShardNumbers() {
        assertThrows(IllegalArgumentException.class, () -> RecoveryWaveInputBuilder.build(
                "job-1", "source", "", "destination", "s3://staging/manifest", 2,
                List.of(1, 1), "documents", "jobs", VersionPolicy.ALL_VERSIONS,
                "us-east-1", 300, 5, "wave"));
        assertThrows(IllegalArgumentException.class, () -> RecoveryWaveInputBuilder.build(
                "job-1", "source", "", "destination", "s3://staging/manifest", 2,
                List.of(2), "documents", "jobs", VersionPolicy.ALL_VERSIONS,
                "us-east-1", 300, 5, "wave"));
    }
}
