
package io.github.awsdocmigration.engine;

import io.github.awsdocmigration.domain.DocumentRecord;
import io.github.awsdocmigration.domain.VersionIdentityType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.*;

class ManifestReaderTest {

    @TempDir
    Path tempDir;

    private Path createManifest(String content) throws Exception {
        Path manifest = tempDir.resolve("manifest.jsonl");
        Files.writeString(manifest, content);
        return manifest;
    }

    private String validRecord(String identityType) {
        String identityField = identityType == null
                ? ""
                : "\"versionIdentityType\":\"" + identityType + "\",";

        return "{"
                + "\"documentId\":\"doc-1001\","
                + "\"versionId\":\"version-1\","
                + identityField
                + "\"fileName\":\"contract.pdf\","
                + "\"relativePath\":\"contracts/contract.pdf\","
                + "\"sizeBytes\":2048,"
                + "\"expectedSha256\":null,"
                + "\"isLatest\":true,"
                + "\"metadata\":{}"
                + "}";
    }

    @Test
    void readsLegacyManifestWithoutVersionIdentityType() throws Exception {
        Path manifest = createManifest(validRecord(null));

        try (ManifestReader reader = new ManifestReader(manifest)) {
            Iterator<DocumentRecord> iterator = reader.iterator();

            assertTrue(iterator.hasNext());

            DocumentRecord record = iterator.next();

            assertEquals(
                    VersionIdentityType.LEGACY_UNSPECIFIED,
                    record.versionIdentityType()
            );

            assertFalse(iterator.hasNext());
        }
    }

    @Test
    void readsSourceVersionIdentityType() throws Exception {
        Path manifest = createManifest(
                validRecord("SOURCE_VERSION")
        );

        try (ManifestReader reader = new ManifestReader(manifest)) {
            DocumentRecord record = reader.iterator().next();

            assertEquals(
                    VersionIdentityType.SOURCE_VERSION,
                    record.versionIdentityType()
            );
        }
    }

    @Test
    void readsSnapshotIdentityType() throws Exception {
        Path manifest = createManifest(
                validRecord("SNAPSHOT")
        );

        try (ManifestReader reader = new ManifestReader(manifest)) {
            DocumentRecord record = reader.iterator().next();

            assertEquals(
                    VersionIdentityType.SNAPSHOT,
                    record.versionIdentityType()
            );
        }
    }

    @Test
    void rejectsUnsupportedVersionIdentityType() throws Exception {
        Path manifest = createManifest(
                validRecord("UNKNOWN_TYPE")
        );

        try (ManifestReader reader = new ManifestReader(manifest)) {
            Iterator<DocumentRecord> iterator = reader.iterator();

            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    iterator::next
            );

            assertTrue(
                    exception.getMessage().contains("line 1")
            );

            assertTrue(
                    exception.getMessage().contains(
                            "Unsupported versionIdentityType"
                    )
            );
        }
    }

    @Test
    void rejectsNonStringVersionIdentityType() throws Exception {
        String record = validRecord(null).replace(
                "\"versionId\":\"version-1\",",
                "\"versionId\":\"version-1\","
                        + "\"versionIdentityType\":123,"
        );

        Path manifest = createManifest(record);

        try (ManifestReader reader = new ManifestReader(manifest)) {
            Iterator<DocumentRecord> iterator = reader.iterator();

            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    iterator::next
            );

            assertTrue(
                    exception.getMessage().contains(
                            "versionIdentityType must be a non-blank string"
                    )
            );
        }
    }

    @Test
    void returnsValidRecordBeforeReportingNextInvalidRecord()
            throws Exception {

        String firstRecord = validRecord("SOURCE_VERSION");

        String secondRecord = "{invalid-json}";

        Path manifest = createManifest(
                firstRecord + "\n" + secondRecord
        );

        try (ManifestReader reader = new ManifestReader(manifest)) {
            Iterator<DocumentRecord> iterator = reader.iterator();

            DocumentRecord first = iterator.next();

            assertEquals("doc-1001", first.documentId());

            assertThrows(
                    IllegalArgumentException.class,
                    iterator::next
            );
        }
    }
}
