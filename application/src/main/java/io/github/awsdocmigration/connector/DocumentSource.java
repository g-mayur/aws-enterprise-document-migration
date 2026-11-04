
package io.github.awsdocmigration.connector;

import io.github.awsdocmigration.domain.DocumentRecord;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Set;

/**
 * Source connector interface implemented by each repository connector.
 *
 * Every connector must document its consistency guarantees and explicitly
 * declare the capabilities it supports.
 */
public interface DocumentSource extends AutoCloseable {

    /**
     * Returns the unique identifier of this connector.
     */
    String connectorId();

    /**
     * Returns the capabilities supported by this connector.
     *
     * The default is an empty set so that existing implementations
     * remain compatible until they explicitly declare their capabilities.
     */
    default Set<ConnectorCapability> capabilities() {
        return Set.of();
    }

    /**
     * Validates connectivity and access to the source system.
     */
    void validateConnection() throws Exception;

    /**
     * Discovers documents available in the source system.
     */
    Iterator<DocumentRecord> discover() throws Exception;

    /**
     * Opens a document version as a streaming input.
     */
    InputStream openStream(DocumentRecord record) throws IOException;

    /**
     * Returns a source checksum when independently available.
     *
     * The default implementation returns the checksum from the manifest.
     * This is not independent source-side checksum verification.
     */
    default String sourceChecksum(DocumentRecord record) {
        return record.expectedSha256();
    }

    /**
     * Releases connector resources.
     */
    @Override
    default void close() throws Exception {
    }
}
