
package io.github.awsdocmigration.connector.filesystem;

import io.github.awsdocmigration.connector.ConnectorCapability;
import io.github.awsdocmigration.domain.DocumentRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FilesystemSourceTest {

    @TempDir
    Path tempDir;

    @Test
    void validatesReadableSourceDirectory() throws Exception {
        FilesystemSource source = new FilesystemSource(tempDir);

        try (source) {
            assertDoesNotThrow(source::validateConnection);
        }
    }

    @Test
    void discoversFiles() throws Exception {
        Path file = tempDir.resolve("contract.pdf");
        Files.writeString(file, "sample contract");

        FilesystemSource source = new FilesystemSource(tempDir);

        try (source) {
            Iterator<DocumentRecord> records = source.discover();

            assertTrue(records.hasNext());

            DocumentRecord record = records.next();

            assertEquals("contract.pdf", record.fileName());
            assertEquals("contract.pdf", record.relativePath());
            assertEquals(15L, record.sizeBytes());
            assertTrue(record.isLatest());

            assertFalse(records.hasNext());
        }
    }

    @Test
    void opensDiscoveredFileAsStream() throws Exception {
        Path file = tempDir.resolve("document.txt");
        Files.writeString(file, "migration test");

        FilesystemSource source = new FilesystemSource(tempDir);

        try (source) {
            DocumentRecord record = source.discover().next();

            try (InputStream input = source.openStream(record)) {
                String content = new String(input.readAllBytes());

                assertEquals("migration test", content);
            }
        }
    }

    @Test
    void rejectsPathOutsideSourceRoot() throws Exception {
        Path outsideFile = tempDir.getParent()
                .resolve("outside-test-file.txt");

        Files.writeString(outsideFile, "outside");

        FilesystemSource source = new FilesystemSource(tempDir);

        DocumentRecord record = new DocumentRecord(
                "outside-test-file.txt",
                "test-v1",
                "outside-test-file.txt",
                "../outside-test-file.txt",
                7,
                null,
                true,
                null
        );

        try (source) {
            assertThrows(
                    SecurityException.class,
                    () -> source.openStream(record)
            );
        } finally {
            Files.deleteIfExists(outsideFile);
        }
    }

    @Test
    void rejectsOperationsAfterClose() throws Exception {
        FilesystemSource source = new FilesystemSource(tempDir);

        source.close();

        assertThrows(
                IllegalStateException.class,
                source::validateConnection
        );

        assertThrows(
                IllegalStateException.class,
                source::discover
        );
    }

    @Test
    void closesIncompleteDiscoveryWhenConnectorCloses()
            throws Exception {

        Files.writeString(
                tempDir.resolve("first.txt"),
                "first"
        );

        Files.writeString(
                tempDir.resolve("second.txt"),
                "second"
        );

        FilesystemSource source = new FilesystemSource(tempDir);

        Iterator<DocumentRecord> records = source.discover();

        assertTrue(records.hasNext());

        source.close();

        assertFalse(records.hasNext());
    }

    @Test
    void declaresExpectedCapabilities() {
        FilesystemSource source = new FilesystemSource(tempDir);

        Set<ConnectorCapability> capabilities =
                source.capabilities();

        assertTrue(
                capabilities.contains(
                        ConnectorCapability.DOCUMENT_DISCOVERY
                )
        );

        assertTrue(
                capabilities.contains(
                        ConnectorCapability.STREAMING_READ
                )
        );

        assertTrue(
                capabilities.contains(
                        ConnectorCapability.LATEST_VERSION_IDENTIFICATION
                )
        );

        assertFalse(
                capabilities.contains(
                        ConnectorCapability.HISTORICAL_VERSION_ENUMERATION
                )
        );

        source.close();
    }
}
