package io.github.awsdocmigration.engine;

import io.github.awsdocmigration.util.Checksums;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Calculates a stable SHA-256 fingerprint of the exact manifest file bytes.
 *
 * The file is read as a stream, so memory usage does not grow with manifest
 * size. Formatting and line-ending changes intentionally produce a different
 * fingerprint because the fingerprint identifies the exact input artifact.
 */
public final class ManifestFingerprint {

    private ManifestFingerprint() {
    }

    /**
     * Calculates the lowercase hexadecimal SHA-256 digest of a manifest.
     *
     * @param manifestPath path to the manifest file
     * @return 64-character lowercase SHA-256 fingerprint
     * @throws IllegalArgumentException if the path is null
     * @throws IOException if the file cannot be read
     */
    public static String calculate(Path manifestPath) throws IOException {
        if (manifestPath == null) {
            throw new IllegalArgumentException("Manifest path is required");
        }

        try (InputStream input = Files.newInputStream(manifestPath)) {
            return Checksums.sha256(input);
        }
    }
}
