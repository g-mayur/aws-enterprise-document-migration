package io.github.awsdocmigration.domain;

/** Lifecycle states for one independently executable migration shard. */
public enum MigrationShardStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED;

    public boolean canTransitionTo(MigrationShardStatus target) {
        if (target == null || target == this) {
            return false;
        }
        return (this == PENDING && target == RUNNING)
                || (this == RUNNING
                    && (target == COMPLETED || target == FAILED));
    }
}
