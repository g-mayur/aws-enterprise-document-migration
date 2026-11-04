
package io.github.awsdocmigration.connector;

/**
 * Capabilities that a document source connector may explicitly support.
 *
 * A connector must only declare capabilities that it actually implements.
 */
public enum ConnectorCapability {

    /** Can enumerate source documents. */
    DOCUMENT_DISCOVERY,

    /** Can provide document content as an InputStream. */
    STREAMING_READ,

    /** Can identify the latest version of a document authoritatively. */
    LATEST_VERSION_IDENTIFICATION,

    /** Can enumerate historical document versions. */
    HISTORICAL_VERSION_ENUMERATION,

    /** Can independently retrieve a checksum from the source system. */
    SOURCE_CHECKSUM,

    /** Can provide a consistent source snapshot for a migration run. */
    CONSISTENT_SNAPSHOT
}
