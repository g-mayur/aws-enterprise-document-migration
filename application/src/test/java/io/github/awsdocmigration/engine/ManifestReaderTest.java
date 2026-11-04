
package io.github.awsdocmigration.engine;

import io.github.awsdocmigration.domain.DocumentRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.*;

class ManifestReaderTest {

    @TempDir
    Path tempDir;

    private String validRecord() {
        return """
                {
                  "documentId": "contract-0001",
                  "versionId": "v1",
                  "fileName": "contract.pdf",
                  "relativePath": "contracts/contract.pdf",
                  "sizeBytes": 12345,
                  "expectedSha256": null,
                  "isLatest": true,
                  "metadata": {
                    "department": "legal"
                  }
                }
                """;
    }

    private Path createManifest(String content) throws IOException {
        Path manifest = tempDir.resolve("test-manifest.jsonl");
        Files.writeString(manifest, content);
        return manifest;
    }

    @Test
    void readsValidRecord() throws Exception {
        Path manifest = createManifest(validRecord());

        try (ManifestReader reader = new ManifestReader(manifest)) {
            Iterator<DocumentRecord> iterator = reader.iterator();

            assertTrue(iterator.hasNext());

            DocumentRecord record = iterator.next();

            assertEquals("contract-0001", record.documentId());
            assertEquals("v1", record.versionId());
            assertEquals("contract.pdf", record.fileName());
            assertEquals(12345L, record.sizeBytes());
            assertTrue(record.isLatest());
            assertEquals("legal", record.metadata().get("department"));

            assertFalse(iterator.hasNext());
        }
    }

    @Test
    void skipsBlankLinesAndComments() throws Exception {
        String content = """
                # Migration manifest
                  
                """ + validRecord();

        Path manifest = createManifest(content);

        try (ManifestReader reader = new ManifestReader(manifest)) {
            Iterator<DocumentRecord> iterator = reader.iterator();

            assertTrue(iterator.hasNext());
            assertEquals("contract-0001", iterator.next().documentId());
            assertFalse(iterator.hasNext());
        }
    }

    @Test
    void rejectsMalformedJsonWithLineNumber() throws Exception {
        String content = """
                # Header

                { invalid json }
                """;

        Path manifest = createManifest(content);

        try (ManifestReader reader = new ManifestReader(manifest)) {
            Iterator<DocumentRecord> iterator = reader.iterator();

            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    iterator::next
            );

            assertTrue(exception.getMessage().contains("line 3"));
        }
    }

    @Test
    void rejectsMissingRequiredFields() throws Exception {
        String content = """
                {
                  "documentId": "contract-0001",
                  "versionId": "v1"
                }
                """;

        Path manifest = createManifest(content);

        try (ManifestReader reader = new ManifestReader(manifest)) {
            Iterator<DocumentRecord> iterator = reader.iterator();

            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    iterator::next
            );

            assertTrue(exception.getMessage().contains("fileName"));
        }
    }

    @Test
    void rejectsIncorrectFieldTypes() throws Exception {
        String content = validRecord().replace(
                "\"isLatest\": true",
                "\"isLatest\": \"true\""
        );

        Path manifest = createManifest(content);

        try (ManifestReader reader = new ManifestReader(manifest)) {
            Iterator<DocumentRecord> iterator = reader.iterator();

            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    iterator::next
            );

            assertTrue(exception.getMessage().contains("isLatest"));
        }
    }

    @Test
    void rejectsUnknownFields() throws Exception {
        String content = validRecord().replace(
                "\"isLatest\": true",
                "\"isLatest\": true,\n\"unexpectedField\": \"value\""
        );

        Path manifest = createManifest(content);

        try (ManifestReader reader = new ManifestReader(manifest)) {
            Iterator<DocumentRecord> iterator = reader.iterator();

            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    iterator::next
            );

            assertTrue(exception.getMessage().contains("unexpectedField"));
        }
    }
}
