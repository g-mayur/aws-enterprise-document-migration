
package io.github.awsdocmigration.connector;

/**
 * Capabilities that a document source connector may explicitly support.
 *
 * A capability must only be declared when the connector actually
 * implements and can guarantee the corresponding behaviour.
 */
public enum ConnectorCapability {

    /** Can enumerate source documents. */
    DOCUMENT_DISCOVERY,

    /** Can provide document content as an InputStream. */
    STREAMING_READ,

    /** Can authoritatively identify the latest document version. */
    LATEST_VERSION_IDENTIFICATION,

    /** Can enumerate historical document versions. */
    HISTORICAL_VERSION_ENUMERATION,

    /** Can independently retrieve a source-side checksum. */
    SOURCE_CHECKSUM,

    /** Can provide a consistent source snapshot for a migration run. */
    CONSISTENT_SNAPSHOT
}
