package io.github.awsdocmigration.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ManifestFingerprintTest {

    @TempDir
    Path tempDir;

    @Test
    void calculatesSha256FromManifestBytes() throws Exception {
        Path manifest = tempDir.resolve("manifest.jsonl");
        Files.writeString(manifest, "abc");

        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                ManifestFingerprint.calculate(manifest)
        );
    }

    @Test
    void calculatesFingerprintForEmptyManifest() throws Exception {
        Path manifest = tempDir.resolve("empty.jsonl");
        Files.createFile(manifest);

        assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                ManifestFingerprint.calculate(manifest)
        );
    }

    @Test
    void rejectsNullManifestPath() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ManifestFingerprint.calculate(null)
        );
    }

    @Test
    void reportsMissingManifestAsIoFailure() {
        Path missing = tempDir.resolve("missing.jsonl");

        assertThrows(
                java.io.IOException.class,
                () -> ManifestFingerprint.calculate(missing)
        );
    }
}
