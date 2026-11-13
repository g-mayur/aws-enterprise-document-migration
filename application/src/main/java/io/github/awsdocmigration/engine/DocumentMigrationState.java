package io.github.awsdocmigration.engine;

import io.github.awsdocmigration.domain.DocumentMigrationStatus;
import io.github.awsdocmigration.domain.VersionIdentityType;

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
        long updatedAtEpochMillis,
        VersionIdentityType versionIdentityType
) {

    /**
     * Backward-compatible constructor for callers that predate typed identity.
     */
    public DocumentMigrationState(
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
        this(
                documentId,
                versionId,
                status,
                attemptCount,
                leaseOwner,
                leaseExpiresAtEpochMillis,
                destinationKey,
                checksum,
                lastError,
                updatedAtEpochMillis,
                VersionIdentityType.LEGACY_UNSPECIFIED
        );
    }

    public DocumentMigrationState {
        if (versionIdentityType == null) {
            versionIdentityType = VersionIdentityType.LEGACY_UNSPECIFIED;
        }
    }
}
