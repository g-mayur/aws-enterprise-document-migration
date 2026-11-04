
package io.github.awsdocmigration.connector;

import io.github.awsdocmigration.domain.DocumentRecord;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Set;

/**
 * SPI implemented by each repository connector.
 *
 * Implementations must document their consistency guarantees and
 * explicitly declare the capabilities they support.
 */
public interface DocumentSource extends AutoCloseable {

    String connectorId();

    /**
     * Returns the capabilities explicitly supported by this connector.
     *
     * Existing third-party implementations remain source-compatible,
     * but must override this method to declare their capabilities.
     */
    default Set<ConnectorCapability> capabilities() {
        return Set.of();
    }

    void validateConnection() throws Exception;

    Iterator<DocumentRecord> discover() throws Exception;

    InputStream openStream(DocumentRecord record) throws IOException;

    /**
     * Returns an independently retrieved source checksum when supported.
     *
     * The default implementation returns the manifest checksum.
     * This is not independent source-side verification.
     */
    default String sourceChecksum(DocumentRecord record) {
        return record.expectedSha256();
    }

    @Override
    default void close() throws Exception {
    }
}
