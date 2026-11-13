
package io.github.awsdocmigration.domain;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Represents one physical version or observed snapshot of a source document.
 *
 * The version identity type distinguishes source-managed versions from
 * snapshots and records created using older manifest formats.
 */
public record DocumentRecord(
        String documentId,
        String versionId,
        String fileName,
        String relativePath,
        long sizeBytes,
        String expectedSha256,
        boolean isLatest,
        Map<String, String> metadata,
        VersionIdentityType versionIdentityType
) {

    private static final Pattern SHA256_PATTERN =
            Pattern.compile("^[A-Fa-f0-9]{64}$");

    /**
     * Validates the document record and applies safe defaults.
     */
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

        versionIdentityType = versionIdentityType == null
                ? VersionIdentityType.LEGACY_UNSPECIFIED
                : versionIdentityType;
    }

    /**
     * Backward-compatible constructor for existing code and older records.
     *
     * Records created through this constructor have an unspecified
     * version identity because their identity type was not explicitly given.
     */
    public DocumentRecord(
            String documentId,
            String versionId,
            String fileName,
            String relativePath,
            long sizeBytes,
            String expectedSha256,
            boolean isLatest,
            Map<String, String> metadata
    ) {
        this(
                documentId,
                versionId,
                fileName,
                relativePath,
                sizeBytes,
                expectedSha256,
                isLatest,
                metadata,
                VersionIdentityType.LEGACY_UNSPECIFIED
        );
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
