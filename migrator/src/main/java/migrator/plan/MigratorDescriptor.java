package migrator.plan;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.LinkedHashSet;
import java.util.Set;

import migrator.ClassMigrator;

/**
 * Descriptor containing metadata about a {@link ClassMigrator} implementation.
 *
 * <p>A MigratorDescriptor extracts and stores:
 * <ul>
 *   <li>The source class type (from {@code ClassMigrator<OldT, NewT>})</li>
 *   <li>The target class type</li>
 *   <li>The instantiated migrator instance</li>
 *   <li>The common interface shared by source and target classes</li>
 * </ul>
 *
 * <p>The common interface is inferred automatically by searching the class
 * hierarchy for an interface implemented by both the source and target types.
 * This interface is used for type-safe container updates during migration.
 *
 * @see ClassMigrator
 * @see MigrationPlan
 * @see migrator.registry.RegistryUpdater
 */
public final class MigratorDescriptor {

    private final Class<?> from;
    private final Class<?> to;
    private final ClassMigrator<?, ?> migrator;
    private final Class<?> commonInterface;

    /**
     * Creates a new migrator descriptor from the given migrator class.
     *
     * <p>The migrator class must:
     * <ul>
     *   <li>Implement {@code ClassMigrator<OldT, NewT>} with concrete type parameters</li>
     *   <li>Have a public no-arg constructor</li>
     *   <li>Have source and target types that share a common interface</li>
     * </ul>
     *
     * @param migratorClass the migrator implementation class
     * @throws IllegalArgumentException if the class cannot be instantiated,
     *         doesn't implement ClassMigrator with type parameters, or if
     *         source and target types don't share a common interface
     */
    public MigratorDescriptor(Class<? extends ClassMigrator<?, ?>> migratorClass) {
        try {
            this.migrator = migratorClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "Cannot instantiate migrator: " + migratorClass.getName(), e
            );
        }

        // Extract generics from ClassMigrator<OldT, NewT>
        Type[] interfaces = migratorClass.getGenericInterfaces();
        ParameterizedType paramType = null;

        for (Type t : interfaces) {
            if (t instanceof ParameterizedType pt
                    && pt.getRawType() instanceof Class
                    && ClassMigrator.class.isAssignableFrom((Class<?>) pt.getRawType())) {
                paramType = pt;
                break;
            }
        }

        if (paramType == null) {
            throw new IllegalArgumentException(
                "Migrator must implement ClassMigrator<Old, New>"
            );
        }

        Type[] args = paramType.getActualTypeArguments();
        this.from = (Class<?>) args[0];
        this.to = (Class<?>) args[1];
        this.commonInterface = inferCommonInterface(from, to);
    }

    /**
     * Find a common interface that both 'from' and 'to' implement.
     * Searches the full class hierarchy including superclasses and superinterfaces.
     */
    private static Class<?> inferCommonInterface(Class<?> from, Class<?> to) {
        Set<Class<?>> fromInterfaces = getAllInterfaces(from);

        for (Class<?> iface : fromInterfaces) {
            if (iface.isAssignableFrom(to)) {
                return iface;
            }
        }
        throw new IllegalArgumentException(
            "Cannot determine common interface between " + from.getName() + " and " + to.getName()
        );
    }

    /**
     * Collect all interfaces from a class, including those inherited from superclasses
     * and superinterfaces.
     */
    private static Set<Class<?>> getAllInterfaces(Class<?> cls) {
        Set<Class<?>> interfaces = new LinkedHashSet<>();
        collectInterfaces(cls, interfaces);
        return interfaces;
    }

    private static void collectInterfaces(Class<?> cls, Set<Class<?>> result) {
        if (cls == null || cls == Object.class) {
            return;
        }

        for (Class<?> iface : cls.getInterfaces()) {
            if (result.add(iface)) {
                // Also collect superinterfaces
                collectInterfaces(iface, result);
            }
        }

        // Check superclass hierarchy
        collectInterfaces(cls.getSuperclass(), result);
    }

    /**
     * Returns the source class type being migrated from.
     *
     * @return the source class (never null)
     */
    public Class<?> from() { return from; }

    /**
     * Returns the target class type being migrated to.
     *
     * @return the target class (never null)
     */
    public Class<?> to() { return to; }

    /**
     * Returns the instantiated migrator object.
     *
     * @return the migrator instance (never null)
     */
    public ClassMigrator<?, ?> migrator() { return migrator; }

    /**
     * Returns the common interface shared by source and target classes.
     *
     * @return the common interface (never null)
     */
    public Class<?> commonInterface() { return commonInterface; }
}
