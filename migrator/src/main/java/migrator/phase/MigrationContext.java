package migrator.phase;

import migrator.plan.MigrationPlan;

/**
 * Context information delivered to {@link MigrationPhaseListener} callbacks.
 *
 * <p>Provides information about the current migration including:
 * <ul>
 *   <li>The migration plan being executed</li>
 *   <li>A unique migration ID for logging/tracking</li>
 *   <li>Timing information</li>
 * </ul>
 */
public final class MigrationContext {

    private final MigrationPlan plan;
    private final long migrationId;
    private final long startedAtNanos;

    public MigrationContext(MigrationPlan plan, long migrationId) {
        this.plan = plan;
        this.migrationId = migrationId;
        this.startedAtNanos = System.nanoTime();
    }

    /** The migration plan being executed. */
    public MigrationPlan plan() {
        return plan;
    }

    /** Unique identifier for this migration execution. */
    public long migrationId() {
        return migrationId;
    }

    /** Timestamp (in nanos) when this migration started. */
    public long startedAtNanos() {
        return startedAtNanos;
    }
}
