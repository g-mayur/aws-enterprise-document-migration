
package io.github.awsdocmigration.domain;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Represents one physical version of a source document.
 *
 * A record must contain enough stable information to identify and
 * retrieve the document version across migration retries.
 */
public record DocumentRecord(
        String documentId,
        String versionId,
        String fileName,
        String relativePath,
        long sizeBytes,
        String expectedSha256,
        boolean isLatest,
        Map<String, String> metadata
) {

    private static final Pattern SHA256_PATTERN =
            Pattern.compile("^[A-Fa-f0-9]{64}$");

    public DocumentRecord {

        requireText(documentId, "documentId");
        requireText(versionId, "versionId");
        requireText(fileName, "fileName");
        requireText(relativePath, "relativePath");

        if (sizeBytes < 0) {
            throw new IllegalArgumentException(
                    "sizeBytes cannot be negative"
            );
        }

        if (expectedSha256 != null
                && !SHA256_PATTERN.matcher(expectedSha256).matches()) {
            throw new IllegalArgumentException(
                    "expectedSha256 must contain exactly 64 hexadecimal characters"
            );
        }

        metadata = metadata == null
                ? Map.of()
                : Map.copyOf(metadata);
    }

    /**
     * Returns a stable identifier for a document version.
     */
    public String idempotencyKey() {
        return documentId + "#" + versionId;
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " is required"
            );
        }
    }
}
