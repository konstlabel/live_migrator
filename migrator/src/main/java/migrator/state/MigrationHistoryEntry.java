package migrator.state;

import migrator.metrics.MigrationMetrics;

import java.time.Instant;

/**
 * Immutable record representing a single migration execution in history.
 *
 * @param migrationId unique identifier for the migration
 * @param timestamp when the migration completed (or failed)
 * @param status final status of the migration
 * @param metrics metrics from the migration (null if failed before completion)
 * @param errorMessage error message if migration failed (null on success)
 */
public record MigrationHistoryEntry(
        long migrationId,
        Instant timestamp,
        MigrationState.Status status,
        MigrationMetrics metrics,
        String errorMessage
) {
    /**
     * Create a successful migration history entry.
     */
    public static MigrationHistoryEntry success(long migrationId, MigrationMetrics metrics) {
        return new MigrationHistoryEntry(
                migrationId,
                Instant.now(),
                MigrationState.Status.SUCCESS,
                metrics,
                null
        );
    }

    /**
     * Create a failed migration history entry.
     */
    public static MigrationHistoryEntry failure(long migrationId, String errorMessage, MigrationMetrics partialMetrics) {
        return new MigrationHistoryEntry(
                migrationId,
                Instant.now(),
                MigrationState.Status.FAILED,
                partialMetrics,
                errorMessage
        );
    }
}
