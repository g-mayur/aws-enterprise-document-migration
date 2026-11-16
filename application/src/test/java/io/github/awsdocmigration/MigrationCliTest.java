
package io.github.awsdocmigration;

import io.github.awsdocmigration.connector.DocumentSource;
import io.github.awsdocmigration.domain.DocumentRecord;
import io.github.awsdocmigration.domain.VersionIdentityType;
import io.github.awsdocmigration.engine.ManifestReader;
import io.github.awsdocmigration.engine.RecoveryJobConfiguration;
import io.github.awsdocmigration.domain.VersionPolicy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

class MigrationCliTest {

    @TempDir
    Path tempDir;

    private DocumentRecord createRecord(String id) {

        return new DocumentRecord(
                id,
                "version-1",
                id + ".pdf",
                "documents/" + id + ".pdf",
                1024,
                null,
                true,
                Map.of("department", "legal"),
                VersionIdentityType.SOURCE_VERSION
        );
    }

    private static class TestDocumentSource implements DocumentSource {

        private final Iterator<DocumentRecord> records;
        private boolean validated;
        private boolean closed;

        TestDocumentSource(List<DocumentRecord> records) {
            this.records = records.iterator();
        }

        @Override
        public String connectorId() {
            return "test";
        }

        @Override
        public void validateConnection() {
            validated = true;
        }

        @Override
        public Iterator<DocumentRecord> discover() {
            return records;
        }

        @Override
        public InputStream openStream(DocumentRecord record) {
            throw new UnsupportedOperationException(
                    "Not required for manifest generation tests"
            );
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @Test
    void generatesManifestFromDiscoveredRecords() throws Exception {

        Path manifest = tempDir.resolve("generated.jsonl");

        TestDocumentSource source = new TestDocumentSource(
                List.of(
                        createRecord("document-1"),
                        createRecord("document-2")
                )
        );

        MigrationCli.generateManifest(source, manifest);

        assertTrue(Files.exists(manifest));
        assertTrue(source.validated);
        assertTrue(source.closed);

        try (ManifestReader reader = new ManifestReader(manifest)) {

            Iterator<DocumentRecord> records = reader.iterator();

            assertTrue(records.hasNext());

            DocumentRecord first = records.next();

            assertEquals("document-1", first.documentId());
            assertEquals("version-1", first.versionId());
            assertEquals(
                    VersionIdentityType.SOURCE_VERSION,
                    first.versionIdentityType()
            );

            assertTrue(records.hasNext());

            DocumentRecord second = records.next();

            assertEquals("document-2", second.documentId());

            assertFalse(records.hasNext());
        }
    }

    @Test
    void createsEmptyManifestWhenSourceContainsNoDocuments()
            throws Exception {

        Path manifest = tempDir.resolve("empty.jsonl");

        TestDocumentSource source =
                new TestDocumentSource(List.of());

        MigrationCli.generateManifest(source, manifest);

        assertTrue(Files.exists(manifest));
        assertEquals(0, Files.size(manifest));
        assertTrue(source.closed);
    }

    @Test
    void doesNotOverwriteExistingManifest() throws Exception {

        Path manifest = tempDir.resolve("existing.jsonl");

        Files.writeString(manifest, "original content");

        TestDocumentSource source =
                new TestDocumentSource(List.of(createRecord("document-1")));

        assertThrows(
                IOException.class,
                () -> MigrationCli.generateManifest(source, manifest)
        );

        assertEquals(
                "original content",
                Files.readString(manifest)
        );

        assertTrue(source.closed);
    }

    @Test
    void removesIncompleteManifestWhenDiscoveryFails() throws Exception {

        Path manifest = tempDir.resolve("failed.jsonl");

        TestDocumentSource source = new TestDocumentSource(
                List.of(createRecord("document-1"))
        ) {
            @Override
            public Iterator<DocumentRecord> discover() {

                return new Iterator<>() {

                    private boolean first = true;

                    @Override
                    public boolean hasNext() {
                        return true;
                    }

                    @Override
                    public DocumentRecord next() {

                        if (first) {
                            first = false;
                            return createRecord("document-1");
                        }

                        throw new IllegalStateException(
                                "Simulated discovery failure"
                        );
                    }
                };
            }
        };

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> MigrationCli.generateManifest(source, manifest)
        );

        assertEquals(
                "Simulated discovery failure",
                exception.getMessage()
        );

        assertFalse(Files.exists(manifest));
        assertTrue(source.closed);
    }

    @Test
    void rejectsNullSource() {

        Path manifest = tempDir.resolve("null-source.jsonl");

        assertThrows(
                IllegalArgumentException.class,
                () -> MigrationCli.generateManifest(null, manifest)
        );
    }

    @Test
    void recoveryDispatchRequiresExplicitOperatorAndPriorExecutionConfirmation() {
        assertThrows(
                IllegalArgumentException.class,
                () -> MigrationCli.validateRecoveryConfirmation(Map.of("confirm-recovery", "false"))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> MigrationCli.validateRecoveryConfirmation(Map.of("confirm-recovery", "true"))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> MigrationCli.validateRecoveryConfirmation(Map.of(
                        "confirm-recovery", "true",
                        "confirm-prior-execution-terminal", "false"))
        );
        assertDoesNotThrow(
                () -> MigrationCli.validateRecoveryConfirmation(Map.of(
                        "confirm-recovery", "true",
                        "confirm-prior-execution-terminal", "true"))
        );
    }

    @Test
    void recoveryDispatchRequiresMatchingImmutableConfigurationTableAndRegion() {
        RecoveryJobConfiguration configuration = new RecoveryJobConfiguration(
                "a".repeat(64),
                "source-bucket",
                "exports/",
                "destination-bucket",
                "s3://staging/manifests/job-1.jsonl",
                "document-state",
                "job-state",
                "arn:aws:states:us-east-1:123456789012:stateMachine:migration",
                VersionPolicy.ALL_VERSIONS,
                "us-east-1",
                300,
                5,
                20
        );

        assertDoesNotThrow(() -> MigrationCli.validateRecoveryConfiguration(
                configuration, "job-state", "us-east-1"));
        assertThrows(IllegalArgumentException.class, () -> MigrationCli.validateRecoveryConfiguration(
                configuration, "different-job-state", "us-east-1"));
        assertThrows(IllegalArgumentException.class, () -> MigrationCli.validateRecoveryConfiguration(
                configuration, "job-state", "us-west-2"));
        assertThrows(IllegalArgumentException.class, () -> MigrationCli.validateRecoveryConfiguration(
                null, "job-state", "us-east-1"));
    }

    @Test
    void acceptsCompleteShardedExecutionConfiguration() {
        Map<String, String> arguments = Map.of(
                "job-state-table", "migration-jobs",
                "state-table", "migration-documents",
                "job-id", "job-2026-001",
                "total-shards", "8",
                "shard-number", "3"
        );

        assertDoesNotThrow(
                () -> MigrationCli.validateShardedExecutionArguments(arguments)
        );
    }

    @Test
    void rejectsShardedExecutionWithoutDocumentStateTable() {
        Map<String, String> arguments = Map.of(
                "job-state-table", "migration-jobs",
                "job-id", "job-2026-001",
                "total-shards", "8",
                "shard-number", "3"
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> MigrationCli.validateShardedExecutionArguments(arguments)
        );
    }

    @Test
    void rejectsShardNumberOutsideConfiguredRange() {
        Map<String, String> arguments = Map.of(
                "job-state-table", "migration-jobs",
                "state-table", "migration-documents",
                "job-id", "job-2026-001",
                "total-shards", "8",
                "shard-number", "8"
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> MigrationCli.validateShardedExecutionArguments(arguments)
        );
    }

    @Test
    void rejectsTotalShardsWithoutJobStateTable() {
        Map<String, String> arguments = Map.of(
                "total-shards", "8"
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> MigrationCli.validateShardedExecutionArguments(arguments)
        );
    }

}
