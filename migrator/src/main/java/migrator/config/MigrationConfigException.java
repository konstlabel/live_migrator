package migrator.config;

/**
 * Exception thrown when migration configuration cannot be loaded or is invalid.
 */
public class MigrationConfigException extends RuntimeException {

    public MigrationConfigException(String message) {
        super(message);
    }

    public MigrationConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
