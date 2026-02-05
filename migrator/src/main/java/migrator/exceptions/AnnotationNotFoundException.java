package migrator.exceptions;

/**
 * Exception thrown when a required migration annotation is not found during classpath scanning.
 *
 * <p>The migration framework requires certain annotated classes to be present
 * (e.g., {@link migrator.annotations.Migrator}, {@link migrator.annotations.SmokeTestComponent}).
 * This exception is thrown when the {@link migrator.scanner.AnnotationScanner} cannot
 * locate a required annotated class.
 *
 * @see migrator.scanner.AnnotationScanner
 */
public class AnnotationNotFoundException extends RuntimeException {
    public AnnotationNotFoundException(String message) {
        super(message);
    }
}
