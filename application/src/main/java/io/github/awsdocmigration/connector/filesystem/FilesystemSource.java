
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
import java.util.stream.Stream;

/**
 * Filesystem source connector.
 *
 * Supports lazy discovery and streaming reads of files.
 * Each discovered file is represented as one logical version.
 *
 * Historical filesystem versions are not enumerated.
 *
 * Discovery resources are released automatically when iteration
 * completes or when the connector is closed.
 */
public final class FilesystemSource implements DocumentSource {

    private final Path root;

    private Stream<Path> discoveryStream;
    private boolean closed;

    public FilesystemSource(Path root) {
        if (root == null) {
            throw new IllegalArgumentException(
                    "Source root is required"
            );
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
        ensureOpen();

        if (!Files.isDirectory(root) || !Files.isReadable(root)) {
            throw new IOException(
                    "Source root is not a readable directory: " + root
            );
        }
    }

    @Override
    public Iterator<DocumentRecord> discover() throws IOException {
        ensureOpen();

        closeDiscoveryStream();

        final Stream<Path> paths = Files.walk(root);
        discoveryStream = paths;

        final Iterator<Path> iterator = paths
                .filter(Files::isRegularFile)
                .iterator();

        return new Iterator<>() {

            private void closeThisDiscovery() {
                if (discoveryStream == paths) {
                    paths.close();
                    discoveryStream = null;
                }
            }

            @Override
            public boolean hasNext() {
                if (closed || discoveryStream != paths) {
                    return false;
                }

                try {
                    boolean more = iterator.hasNext();

                    if (!more) {
                        closeThisDiscovery();
                    }

                    return more;

                } catch (RuntimeException e) {
                    closeThisDiscovery();
                    throw e;
                }
            }

            @Override
            public DocumentRecord next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }

                try {
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
                    closeThisDiscovery();
                    throw new UncheckedIOException(e);

                } catch (RuntimeException e) {
                    closeThisDiscovery();
                    throw e;
                }
            }
        };
    }

    @Override
    public InputStream openStream(
            DocumentRecord record
    ) throws IOException {
        ensureOpen();

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

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            closeDiscoveryStream();
        }
    }

    private void closeDiscoveryStream() {
        if (discoveryStream != null) {
            discoveryStream.close();
            discoveryStream = null;
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "Filesystem source connector is already closed"
            );
        }
    }
}
