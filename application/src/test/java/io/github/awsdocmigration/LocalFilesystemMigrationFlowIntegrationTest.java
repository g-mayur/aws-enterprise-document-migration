package io.github.awsdocmigration;

import io.github.awsdocmigration.connector.filesystem.FilesystemSource;
import io.github.awsdocmigration.domain.DocumentRecord;
import io.github.awsdocmigration.domain.VersionPolicy;
import io.github.awsdocmigration.engine.DestinationKeyResolver;
import io.github.awsdocmigration.engine.ManifestReader;
import io.github.awsdocmigration.storage.DestinationWriter;
import io.github.awsdocmigration.util.Checksums;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LocalFilesystemMigrationFlowIntegrationTest {

    @TempDir
    Path temporaryDirectory;

    private record StoredDocument(
            byte[] content,
            String sha256,
            String documentId,
            String versionId
    ) {
    }

    private static final class InMemoryDestinationWriter
            implements DestinationWriter {

        private final Map<String, StoredDocument> objects = new HashMap<>();

        @Override
        public void write(
                DocumentRecord record,
                String key,
                InputStream content,
                String sha256
        ) throws Exception {
            StoredDocument previous = objects.putIfAbsent(
                    key,
                    new StoredDocument(
                            content.readAllBytes(),
                            sha256,
                            record.documentId(),
                            record.versionId()
                    )
            );
            if (previous != null) {
                throw new IllegalStateException(
                        "Destination key already exists: " + key
                );
            }
        }
    }

    @Test
    void discoversManifestsVerifiesAndTransfersRealFilesystemDocuments()
            throws Exception {

        Path sourceRoot = temporaryDirectory.resolve("source");
        Path manifest = temporaryDirectory.resolve("manifest.jsonl");

        byte[] firstContent = "local migration document one"
                .getBytes(StandardCharsets.UTF_8);
        byte[] secondContent = "nested document two with UTF-8: café"
                .getBytes(StandardCharsets.UTF_8);

        Files.createDirectories(sourceRoot.resolve("contracts"));
        Files.createDirectories(sourceRoot.resolve("finance/2026"));
        Files.write(sourceRoot.resolve("contracts/agreement.txt"), firstContent);
        Files.write(sourceRoot.resolve("finance/2026/report.txt"), secondContent);

        // Exercise the real filesystem connector and JSONL manifest writer.
        MigrationCli.generateManifest(
                new FilesystemSource(sourceRoot),
                manifest
        );

        assertTrue(Files.exists(manifest));
        assertEquals(2, Files.readAllLines(manifest).size());

        InMemoryDestinationWriter destination =
                new InMemoryDestinationWriter();
        DestinationKeyResolver keyResolver = new DestinationKeyResolver();
        int migrated = 0;

        // Re-open the source because manifest generation closes its connector.
        try (
                FilesystemSource source = new FilesystemSource(sourceRoot);
                ManifestReader reader = new ManifestReader(manifest)
        ) {
            source.validateConnection();

            for (DocumentRecord record : reader) {
                var outcome = MigrationCli.migrateDocument(
                        source,
                        destination,
                        keyResolver,
                        record,
                        VersionPolicy.ALL_VERSIONS
                );

                assertNotNull(outcome.destinationKey());
                assertEquals(64, outcome.sha256().length());
                migrated++;
            }
        }

        assertEquals(2, migrated);
        assertEquals(2, destination.objects.size());

        String firstKey = keyResolver.resolve(
                new DocumentRecord(
                        "contracts/agreement.txt",
                        "filesystem-v1",
                        "agreement.txt",
                        "contracts/agreement.txt",
                        firstContent.length,
                        null,
                        true,
                        Map.of()
                ),
                VersionPolicy.ALL_VERSIONS
        );
        String secondKey = keyResolver.resolve(
                new DocumentRecord(
                        "finance/2026/report.txt",
                        "filesystem-v1",
                        "report.txt",
                        "finance/2026/report.txt",
                        secondContent.length,
                        null,
                        true,
                        Map.of()
                ),
                VersionPolicy.ALL_VERSIONS
        );

        StoredDocument firstStored = destination.objects.get(firstKey);
        StoredDocument secondStored = destination.objects.get(secondKey);

        assertNotNull(firstStored);
        assertNotNull(secondStored);
        assertArrayEquals(firstContent, firstStored.content());
        assertArrayEquals(secondContent, secondStored.content());
        assertEquals(
                Checksums.sha256(new ByteArrayInputStream(firstContent)),
                firstStored.sha256()
        );
        assertEquals(
                Checksums.sha256(new ByteArrayInputStream(secondContent)),
                secondStored.sha256()
        );
        assertEquals("contracts/agreement.txt", firstStored.documentId());
        assertEquals("finance/2026/report.txt", secondStored.documentId());
        assertEquals("filesystem-v1", firstStored.versionId());
        assertEquals("filesystem-v1", secondStored.versionId());
    }
}
