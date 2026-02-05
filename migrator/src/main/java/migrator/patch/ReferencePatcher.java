package migrator.patch;

/**
 * Interface for patching object references during migration.
 *
 * <p>Implementations traverse object graphs and replace references to old
 * objects with references to their migrated counterparts using a
 * {@link ForwardingTable}.
 *
 * @see ReflectionReferencePatcher
 * @see ForwardingTable
 */
public interface ReferencePatcher {

    /**
     * Patch all references within the given object graph.
     * Recursively traverses fields, arrays, and collections.
     *
     * @param obj the root object to patch
     */
    void patchObject(Object obj);

    /**
     * Patch static fields of the given class.
     *
     * @param clazz the class whose static fields should be patched
     */
    void patchStaticFields(Class<?> clazz);
}
