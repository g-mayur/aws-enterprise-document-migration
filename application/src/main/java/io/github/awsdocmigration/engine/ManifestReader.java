
package io.github.awsdocmigration.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.awsdocmigration.domain.DocumentRecord;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Reads document records incrementally from a UTF-8 JSON Lines manifest.
 *
 * The reader is single-use. Call close() when processing finishes or stops
 * early. Blank lines and lines beginning with # are ignored.
 */
public final class ManifestReader implements Iterable<DocumentRecord>, AutoCloseable {

    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "documentId",
            "versionId",
            "fileName",
            "relativePath",
            "sizeBytes",
            "expectedSha256",
            "isLatest",
            "metadata"
    );

    private final Path path;
    private final ObjectMapper mapper = new ObjectMapper();

    private BufferedReader reader;
    private boolean iteratorCreated;
    private long lineNumber;

    public ManifestReader(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("Manifest path is required");
        }
        this.path = path;
    }

    @Override
    public Iterator<DocumentRecord> iterator() {
        if (iteratorCreated) {
            throw new IllegalStateException(
                    "ManifestReader supports only one iteration"
            );
        }

        iteratorCreated = true;

        try {
            reader = Files.newBufferedReader(path);
            return new ManifestIterator();
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Unable to open manifest: " + path, e
            );
        }
    }

    private final class ManifestIterator implements Iterator<DocumentRecord> {

        private String nextLine;
        private long nextLineNumber;

        private ManifestIterator() {
            advance();
        }

        private void advance() {
            try {
                String line;

                while ((line = reader.readLine()) != null) {
                    lineNumber++;

                    if (line.isBlank() ||
                            line.stripLeading().startsWith("#")) {
                        continue;
                    }

                    nextLine = line;
                    nextLineNumber = lineNumber;
                    return;
                }

                nextLine = null;
                close();

            } catch (IOException e) {
                closeQuietly();
                throw new UncheckedIOException(
                        "Error reading manifest near line " +
                                (lineNumber + 1), e
                );
            }
        }

        @Override
        public boolean hasNext() {
            return nextLine != null;
        }

        @Override
        public DocumentRecord next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }

            String currentLine = nextLine;
            long currentLineNumber = nextLineNumber;

            try {
                JsonNode node = mapper.readTree(currentLine);

                if (node == null || !node.isObject()) {
                    throw new IllegalArgumentException(
                            "Each manifest line must contain a JSON object"
                    );
                }

                validateRecord(node);

                DocumentRecord record =
                        mapper.treeToValue(node, DocumentRecord.class);

                advance();
                return record;

            } catch (Exception e) {
                closeQuietly();

                throw new IllegalArgumentException(
                        "Invalid manifest record at line " +
                                currentLineNumber + ": " + e.getMessage(),
                        e
                );
            }
        }
    }

    private void validateRecord(JsonNode node) {

        node.fieldNames().forEachRemaining(field -> {
            if (!ALLOWED_FIELDS.contains(field)) {
                throw new IllegalArgumentException(
                        "Unknown field: " + field
                );
            }
        });

        requireText(node, "documentId");
        requireText(node, "versionId");
        requireText(node, "fileName");
        requireText(node, "relativePath");

        JsonNode size = node.get("sizeBytes");

        if (size == null || !size.isIntegralNumber()
                || !size.canConvertToLong() || size.longValue() < 0) {
            throw new IllegalArgumentException(
                    "sizeBytes must be a non-negative integer"
            );
        }

        JsonNode latest = node.get("isLatest");

        if (latest == null || !latest.isBoolean()) {
            throw new IllegalArgumentException(
                    "isLatest must be a boolean"
            );
        }

        JsonNode checksum = node.get("expectedSha256");

        if (checksum != null && !checksum.isNull()
                && !checksum.isTextual()) {
            throw new IllegalArgumentException(
                    "expectedSha256 must be a string or null"
            );
        }

        JsonNode metadata = node.get("metadata");

        if (metadata != null && !metadata.isNull()) {
            if (!metadata.isObject()) {
                throw new IllegalArgumentException(
                        "metadata must be a JSON object"
                );
            }

            metadata.fields().forEachRemaining(entry -> {
                if (!entry.getValue().isTextual()) {
                    throw new IllegalArgumentException(
                            "metadata values must be strings: " +
                                    entry.getKey()
                    );
                }
            });
        }
    }

    private void requireText(JsonNode node, String field) {
        JsonNode value = node.get(field);

        if (value == null || !value.isTextual()
                || value.asText().isBlank()) {
            throw new IllegalArgumentException(
                    field + " is required and must be a non-blank string"
            );
        }
    }

    @Override
    public void close() throws IOException {
        if (reader != null) {
            reader.close();
            reader = null;
        }
    }

    private void closeQuietly() {
        try {
            close();
        } catch (IOException ignored) {
            // Preserve the original parsing or reading exception.
        }
    }
}
