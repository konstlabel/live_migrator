package migrator.exceptions;

/**
 * Exception thrown when a migration operation fails.
 *
 * <p>This exception provides rich diagnostic context including:
 * <ul>
 *   <li>A human-readable error message</li>
 *   <li>The object that caused the failure (serialization-safe representation)</li>
 *   <li>Source and target class types</li>
 *   <li>The migration stage where failure occurred</li>
 * </ul>
 *
 * <p>All diagnostic fields are serialization-safe strings to avoid holding
 * references to potentially problematic objects.
 *
 * @see migrator.engine.MigrationEngine
 * @see migrator.ClassMigrator
 */
public class MigrateException extends Exception {

    /** Safe diagnostic representation of object involved in failure */
    private final String objectIdStr;

    /** identityHashCode of object (if available) */
    private final Integer objectIdentityHash;

    private final Class<?> from;
    private final Class<?> to;
    private final String stage;

    // ---------------- constructors ----------------

    /**
     * Creates a new migration exception with a message.
     *
     * @param message the error message
     */
    public MigrateException(String message) {
        super(message);
        this.objectIdStr = null;
        this.objectIdentityHash = null;
        this.from = null;
        this.to = null;
        this.stage = null;
    }

    /**
     * Creates a new migration exception with a message and cause.
     *
     * @param message the error message
     * @param cause the underlying cause
     */
    public MigrateException(String message, Throwable cause) {
        super(message, cause);
        this.objectIdStr = null;
        this.objectIdentityHash = null;
        this.from = null;
        this.to = null;
        this.stage = null;
    }

    /**
     * Creates a new migration exception with full diagnostic context.
     *
     * @param message the error message
     * @param object the object that caused the failure (will be safely described)
     * @param from the source class type
     * @param to the target class type
     * @param stage the migration stage where failure occurred
     */
    public MigrateException(String message,
                            Object object,
                            Class<?> from,
                            Class<?> to,
                            String stage) {
        super(message);
        this.objectIdStr = safeDescribe(object);
        this.objectIdentityHash = object != null ? System.identityHashCode(object) : null;
        this.from = from;
        this.to = to;
        this.stage = stage;
    }

    /**
     * Creates a new migration exception with full diagnostic context and cause.
     *
     * @param message the error message
     * @param object the object that caused the failure (will be safely described)
     * @param from the source class type
     * @param to the target class type
     * @param stage the migration stage where failure occurred
     * @param cause the underlying cause
     */
    public MigrateException(String message,
                            Object object,
                            Class<?> from,
                            Class<?> to,
                            String stage,
                            Throwable cause) {
        super(message, cause);
        this.objectIdStr = safeDescribe(object);
        this.objectIdentityHash = object != null ? System.identityHashCode(object) : null;
        this.from = from;
        this.to = to;
        this.stage = stage;
    }

    // ---------------- helpers ----------------

    private static String safeDescribe(Object o) {
        if (o == null) return "null";
        try {
            String s = o.toString();
            if (s == null) {
                return o.getClass().getName() + "@" + System.identityHashCode(o);
            }
            return o.getClass().getName() + "@" + System.identityHashCode(o) + " [" + s + "]";
        } catch (Exception e) {
            return o.getClass().getName() + "@" + System.identityHashCode(o) + " [toString failed]";
        }
    }

    // ---------------- getters ----------------

    /**
     * Returns a safe string representation of the object that caused the failure.
     *
     * @return the object description, or null if not set
     */
    public String getObjectIdStr() {
        return objectIdStr;
    }

    /**
     * Returns the identity hash code of the object that caused the failure.
     *
     * @return the identity hash code, or null if not set
     */
    public Integer getObjectIdentityHash() {
        return objectIdentityHash;
    }

    /**
     * Returns the source class type being migrated from.
     *
     * @return the source class, or null if not set
     */
    public Class<?> getFrom() {
        return from;
    }

    /**
     * Returns the target class type being migrated to.
     *
     * @return the target class, or null if not set
     */
    public Class<?> getTo() {
        return to;
    }

    /**
     * Returns the migration stage where the failure occurred.
     *
     * @return the stage name, or null if not set
     */
    public String getStage() {
        return stage;
    }

    // ---------------- diagnostics ----------------

    @Override
    public String getMessage() {
        String base = super.getMessage();
        StringBuilder sb = new StringBuilder(base);

        if (stage != null) sb.append(" [stage=").append(stage).append("]");
        if (from != null) sb.append(" [from=").append(from.getName()).append("]");
        if (to != null) sb.append(" [to=").append(to.getName()).append("]");
        if (objectIdStr != null) sb.append(" [object=").append(objectIdStr).append("]");

        return sb.toString();
    }
}
