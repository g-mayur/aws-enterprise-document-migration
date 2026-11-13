package io.github.awsdocmigration.domain;

/**
 * Durable processing state for one source document version in a migration job.
 */
public enum DocumentMigrationStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED
}
