
package io.github.awsdocmigration.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.awsdocmigration.domain.DocumentRecord;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Writes document records incrementally to a UTF-8 JSON Lines manifest.
 *
 * Each document record is serialized as one JSON object per line.
 * Records are written incrementally to avoid accumulating the complete
 * manifest in memory.
 *
 * The writer does not overwrite an existing manifest.
 * Call close() to flush and release the underlying file resource.
 */
public final class ManifestWriter implements AutoCloseable {

    private final ObjectMapper mapper;
    private final BufferedWriter writer;

    private boolean closed;

    /**
     * Creates a new manifest file.
     *
     * @param path destination path for the manifest
     * @throws IllegalArgumentException if the path is null
     * @throws IOException if the file cannot be created
     */
    public ManifestWriter(Path path) throws IOException {

        if (path == null) {
            throw new IllegalArgumentException(
                    "Manifest path is required"
            );
        }

        this.mapper = new ObjectMapper();

        this.writer = Files.newBufferedWriter(
                path,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
        );
    }

    /**
     * Writes one document record as a JSON Lines entry.
     *
     * @param record document record to write
     * @throws IllegalArgumentException if the record is null
     * @throws IllegalStateException if the writer is already closed
     * @throws IOException if serialization or writing fails
     */
    public void write(DocumentRecord record) throws IOException {

        if (closed) {
            throw new IllegalStateException(
                    "ManifestWriter is already closed"
            );
        }

        if (record == null) {
            throw new IllegalArgumentException(
                    "Document record is required"
            );
        }

        String json = mapper.writeValueAsString(record);

        writer.write(json);
        writer.newLine();
    }

    /**
     * Flushes buffered content and closes the manifest file.
     *
     * Repeated calls are safe.
     *
     * @throws IOException if flushing or closing fails
     */
    @Override
    public void close() throws IOException {

        if (!closed) {
            closed = true;
            writer.close();
        }
    }
}
