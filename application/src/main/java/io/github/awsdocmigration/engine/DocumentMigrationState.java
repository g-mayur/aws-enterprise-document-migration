package io.github.awsdocmigration.engine;

import io.github.awsdocmigration.domain.DocumentMigrationStatus;

/**
 * Read model for the durable state of one document version.
 *
 * Lease timestamps are Unix epoch milliseconds.
 */
public record DocumentMigrationState(
        String documentId,
        String versionId,
        DocumentMigrationStatus status,
        int attemptCount,
        String leaseOwner,
        Long leaseExpiresAtEpochMillis,
        String destinationKey,
        String checksum,
        String lastError,
        long updatedAtEpochMillis
) {
}
