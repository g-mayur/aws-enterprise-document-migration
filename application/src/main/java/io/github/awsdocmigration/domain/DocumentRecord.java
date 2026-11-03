package io.github.awsdocmigration.domain;

import java.util.Map;

/** One physical source document version. The idempotency key must be stable across retries. */
public record DocumentRecord(String documentId, String versionId, String fileName, String relativePath,
        long sizeBytes, String expectedSha256, boolean isLatest, Map<String, String> metadata) {
    public DocumentRecord {
        if (documentId == null || documentId.isBlank()) throw new IllegalArgumentException("documentId is required");
        if (versionId == null || versionId.isBlank()) throw new IllegalArgumentException("versionId is required");
        if (fileName == null || fileName.isBlank()) throw new IllegalArgumentException("fileName is required");
        if (sizeBytes < 0) throw new IllegalArgumentException("sizeBytes cannot be negative");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
    public String idempotencyKey() { return documentId + "#" + versionId; }
}
