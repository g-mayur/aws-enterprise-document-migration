package io.github.awsdocmigration.engine;

/**
 * Read model for the durable state of one document version.
 *
 * Lease timestamps are Unix epoch milliseconds.
 */
public record DocumentMigrationState(
        String documentId,
        String versionId,
        String status,
        int attemptCount,
        String leaseOwner,
        Long leaseExpiresAtEpochMillis,
        String destinationKey,
        String checksum,
        String lastError,
        long updatedAtEpochMillis
) {
}
