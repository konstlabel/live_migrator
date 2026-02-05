package migrator.exceptions;

/**
 * Exception thrown when validation fails during migration.
 *
 * <p>This exception is used for both smoke tests and health checks
 * that verify the application state after migration.
 *
 * @see migrator.smoke.SmokeTest
 * @see migrator.smoke.HealthCheck
 */
public class ValidationException extends Exception {
    public ValidationException() { super(); }
    public ValidationException(String message) { super(message); }
    public ValidationException(String message, Throwable cause) { super(message, cause); }
    public ValidationException(Throwable cause) { super(cause); }
}
