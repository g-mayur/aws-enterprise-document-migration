
package io.github.awsdocmigration.connector.filesystem;

import io.github.awsdocmigration.connector.ConnectorCapability;
import io.github.awsdocmigration.connector.DocumentSource;
import io.github.awsdocmigration.domain.DocumentRecord;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Filesystem source connector.
 *
 * Supports lazy discovery and streaming reads of files.
 * Each discovered file is represented as one logical version.
 *
 * This connector does not enumerate historical filesystem versions.
 */
public final class FilesystemSource implements DocumentSource {

    private final Path root;

    public FilesystemSource(Path root) {
        if (root == null) {
            throw new IllegalArgumentException("Source root is required");
        }

        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public String connectorId() {
        return "filesystem";
    }

    @Override
    public Set<ConnectorCapability> capabilities() {
        return Set.of(
                ConnectorCapability.DOCUMENT_DISCOVERY,
                ConnectorCapability.STREAMING_READ,
                ConnectorCapability.LATEST_VERSION_IDENTIFICATION
        );
    }

    @Override
    public void validateConnection() throws IOException {
        if (!Files.isDirectory(root) || !Files.isReadable(root)) {
            throw new IOException(
                    "Source root is not a readable directory: " + root
            );
        }
    }

    @Override
    public Iterator<DocumentRecord> discover() throws IOException {

        final var paths = Files.walk(root);

        final var iterator = paths
                .filter(Files::isRegularFile)
                .iterator();

        return new Iterator<>() {

            private boolean closed;

            private void closePaths() {
                if (!closed) {
                    paths.close();
                    closed = true;
                }
            }

            @Override
            public boolean hasNext() {
                boolean more = iterator.hasNext();

                if (!more) {
                    closePaths();
                }

                return more;
            }

            @Override
            public DocumentRecord next() {

                if (!hasNext()) {
                    throw new NoSuchElementException();
                }

                Path file = iterator.next()
                        .toAbsolutePath()
                        .normalize();

                if (!file.startsWith(root)) {
                    throw new SecurityException(
                            "Path escaped source root"
                    );
                }

                String relative = root.relativize(file)
                        .toString()
                        .replace('\\', '/');

                try {
                    return new DocumentRecord(
                            relative,
                            "filesystem-v1",
                            file.getFileName().toString(),
                            relative,
                            Files.size(file),
                            null,
                            true,
                            Map.of()
                    );
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        };
    }

    @Override
    public InputStream openStream(DocumentRecord record) throws IOException {

        if (record == null) {
            throw new IllegalArgumentException(
                    "Document record is required"
            );
        }

        Path candidate = root.resolve(record.relativePath())
                .normalize();

        if (!candidate.startsWith(root)) {
            throw new SecurityException(
                    "Manifest path escapes source root"
            );
        }

        Path realRoot = root.toRealPath();
        Path realCandidate = candidate.toRealPath();

        if (!realCandidate.startsWith(realRoot)) {
            throw new SecurityException(
                    "Resolved file is outside the source root"
            );
        }

        if (!Files.isRegularFile(realCandidate)
                || !Files.isReadable(realCandidate)) {
            throw new IOException(
                    "Source file is not a readable regular file: "
                            + record.relativePath()
            );
        }

        return Files.newInputStream(realCandidate);
    }
}
