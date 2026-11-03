package io.github.awsdocmigration.connector;

import io.github.awsdocmigration.domain.DocumentRecord;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;

/** SPI implemented by each repository connector. Implementations must document their consistency guarantees. */
public interface DocumentSource extends AutoCloseable {
    String connectorId();
    void validateConnection() throws Exception;
    Iterator<DocumentRecord> discover() throws Exception;
    InputStream openStream(DocumentRecord record) throws IOException;
    default String sourceChecksum(DocumentRecord record) { return record.expectedSha256(); }
    @Override default void close() throws Exception { }
}
