
package io.github.awsdocmigration.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.awsdocmigration.domain.DocumentRecord;
import io.github.awsdocmigration.domain.VersionIdentityType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ManifestWriterTest {

    @TempDir
    Path tempDir;

    private DocumentRecord createRecord(
            String documentId,
            String versionId,
            VersionIdentityType identityType) {

        return new DocumentRecord(
                documentId,
                versionId,
                "contract.pdf",
                "contracts/contract.pdf",
                2048,
                null,
                true,
                Map.of("department", "legal"),
                identityType
        );
    }

    @Test
    void writesDocumentRecordAsJsonLine() throws Exception {
        Path manifest = tempDir.resolve("manifest.jsonl");

        DocumentRecord record = createRecord(
                "doc-1001",
                "version-1",
                VersionIdentityType.SOURCE_VERSION
        );

        try (ManifestWriter writer = new ManifestWriter(manifest)) {
            writer.write(record);
        }

        List<String> lines = Files.readAllLines(manifest);

        assertEquals(1, lines.size());

        JsonNode json = new ObjectMapper().readTree(lines.get(0));

        assertEquals("doc-1001", json.get("documentId").asText());
        assertEquals("version-1", json.get("versionId").asText());
        assertEquals(
                "SOURCE_VERSION",
                json.get("versionIdentityType").asText()
        );
        assertEquals(2048, json.get("sizeBytes").asLong());
    }

    @Test
    void writesMultipleRecordsIncrementally() throws Exception {
        Path manifest = tempDir.resolve("manifest.jsonl");

        try (ManifestWriter writer = new ManifestWriter(manifest)) {
            writer.write(createRecord(
                    "doc-1001",
                    "version-1",
                    VersionIdentityType.SOURCE_VERSION
            ));

            writer.write(createRecord(
                    "doc-1002",
                    "snapshot-1",
                    VersionIdentityType.SNAPSHOT
            ));
        }

        List<String> lines = Files.readAllLines(manifest);

        assertEquals(2, lines.size());

        ObjectMapper mapper = new ObjectMapper();

        assertEquals(
                "doc-1001",
                mapper.readTree(lines.get(0)).get("documentId").asText()
        );

        assertEquals(
                "doc-1002",
                mapper.readTree(lines.get(1)).get("documentId").asText()
        );
    }

    @Test
    void preservesSnapshotIdentityType() throws Exception {
        Path manifest = tempDir.resolve("snapshot.jsonl");

        try (ManifestWriter writer = new ManifestWriter(manifest)) {
            writer.write(createRecord(
                    "doc-1001",
                    "snapshot-1",
                    VersionIdentityType.SNAPSHOT
            ));
        }

        JsonNode json = new ObjectMapper().readTree(
                Files.readString(manifest).strip()
        );

        assertEquals(
                "SNAPSHOT",
                json.get("versionIdentityType").asText()
        );
    }

    @Test
    void rejectsNullDocumentRecord() throws Exception {
        Path manifest = tempDir.resolve("null-record.jsonl");

        try (ManifestWriter writer = new ManifestWriter(manifest)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> writer.write(null)
            );
        }
    }

    @Test
    void rejectsWritingAfterClose() throws Exception {
        Path manifest = tempDir.resolve("closed.jsonl");

        ManifestWriter writer = new ManifestWriter(manifest);

        writer.close();

        assertThrows(
                IllegalStateException.class,
                () -> writer.write(createRecord(
                        "doc-1001",
                        "version-1",
                        VersionIdentityType.SOURCE_VERSION
                ))
        );
    }

    @Test
    void doesNotOverwriteExistingManifest() throws Exception {
        Path manifest = tempDir.resolve("existing.jsonl");

        Files.writeString(manifest, "existing content");

        assertThrows(
                IOException.class,
                () -> new ManifestWriter(manifest)
        );

        assertEquals(
                "existing content",
                Files.readString(manifest)
        );
    }
}
