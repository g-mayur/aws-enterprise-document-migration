
package io.github.awsdocmigration.domain;

/**
 * Describes how a document version is identified by its source.
 *
 * SOURCE_VERSION:
 * The source platform provides an explicit version identifier.
 *
 * SNAPSHOT:
 * The identity represents an observed state of a document,
 * rather than a source-managed historical version.
 *
 * LEGACY_UNSPECIFIED:
 * The identity comes from an older manifest that does not
 * explicitly declare its version identity type.
 */
public enum VersionIdentityType {

    SOURCE_VERSION,

    SNAPSHOT,

    LEGACY_UNSPECIFIED
}
