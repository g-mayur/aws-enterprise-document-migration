package io.github.awsdocmigration.domain;

/** Lifecycle states for a complete migration job. */
public enum MigrationJobStatus {
    CREATED,
    DISCOVERING,
    READY,
    RUNNING,
    COMPLETED,
    COMPLETED_WITH_ERRORS,
    FAILED,
    CANCELLED;

    public boolean canTransitionTo(MigrationJobStatus target) {
        if (target == null || this == target || isTerminal()) {
            return false;
        }
        return switch (this) {
            case CREATED -> target == DISCOVERING || target == FAILED || target == CANCELLED;
            case DISCOVERING -> target == READY || target == FAILED || target == CANCELLED;
            case READY -> target == RUNNING || target == FAILED || target == CANCELLED;
            case RUNNING -> target == COMPLETED || target == COMPLETED_WITH_ERRORS
                    || target == FAILED || target == CANCELLED;
            case COMPLETED, COMPLETED_WITH_ERRORS, FAILED, CANCELLED -> false;
        };
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == COMPLETED_WITH_ERRORS
                || this == FAILED || this == CANCELLED;
    }
}
