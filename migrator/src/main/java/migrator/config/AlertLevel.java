package migrator.config;

/**
 * Alert level for migration logging.
 * Controls the minimum severity of events that get logged.
 */
public enum AlertLevel {
    /**
     * Log all events including debug information.
     */
    DEBUG,

    /**
     * Log warnings and errors only.
     */
    WARNING,

    /**
     * Log errors only.
     */
    ERROR
}
