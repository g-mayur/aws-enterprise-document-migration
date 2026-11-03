package io.github.awsdocmigration.connector.filesystem;

import io.github.awsdocmigration.connector.DocumentSource;
import io.github.awsdocmigration.domain.DocumentRecord;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;

/** Lazy single-version filesystem connector for development and controlled file-share migrations. */
public final class FilesystemSource implements DocumentSource {
    private final Path root;
    public FilesystemSource(Path root) { this.root = root.toAbsolutePath().normalize(); }
    public String connectorId() { return "filesystem"; }
    public void validateConnection() throws IOException {
        if (!Files.isDirectory(root) || !Files.isReadable(root)) throw new IOException("Source root is not a readable directory: " + root);
    }
    public Iterator<DocumentRecord> discover() throws IOException {
        final var paths = Files.walk(root);
        final var iterator = paths.filter(Files::isRegularFile).iterator();
        return new Iterator<>() {
            public boolean hasNext() {
                boolean more = iterator.hasNext();
                if (!more) paths.close();
                return more;
            }
            public DocumentRecord next() {
                if (!hasNext()) throw new NoSuchElementException();
                Path p = iterator.next().toAbsolutePath().normalize();
                if (!p.startsWith(root)) throw new SecurityException("Path escaped source root");
                String relative = root.relativize(p).toString().replace('\\', '/');
                try { return new DocumentRecord(relative, "filesystem-v1", p.getFileName().toString(), relative, Files.size(p), null, true, Map.of()); }
                catch (IOException e) { throw new UncheckedIOException(e); }
            }
        };
    }
    public InputStream openStream(DocumentRecord record) throws IOException {
        Path candidate = root.resolve(record.relativePath()).normalize();
        if (!candidate.startsWith(root)) throw new SecurityException("Manifest path escapes source root");
        return Files.newInputStream(candidate);
    }
}
