package migrator.heap;

import java.util.Collection;
import java.util.Set;

import migrator.exceptions.MigrateException;

/**
 * Interface for walking the JVM heap to discover and resolve object references.
 *
 * <p>Implementations of this interface provide mechanisms to:
 * <ul>
 *   <li>Take snapshots of all objects of a given class type</li>
 *   <li>Resolve object references from snapshot tags</li>
 *   <li>Walk the entire heap or a filtered subset</li>
 * </ul>
 *
 * <p>The primary implementation is {@link NativeHeapWalker}, which uses JNI
 * for efficient heap traversal.
 *
 * @see NativeHeapWalker
 * @see HeapSnapshot
 */
public interface HeapWalker {

    /**
     * Takes a snapshot of all objects of a given class type on the heap.
     *
     * @param targetClass the class type to snapshot
     * @return a snapshot containing entries for all instances of the class
     */
    HeapSnapshot snapshot(Class<?> targetClass);

    /**
     * Resolves an object reference from a snapshot tag.
     *
     * @param tag the tag from a {@link HeapSnapshot.Entry}
     * @return the resolved object, or null if the object has been garbage collected
     */
    Object resolve(long tag);

    /**
     * Walk the entire heap and return all live objects.
     *
     * <p>This method performs a full heap traversal, which can be expensive
     * for large heaps. Use {@link #walkHeap(Collection)} when you know
     * which classes to target for better performance.
     *
     * @return an identity-based set of all live objects on the heap
     * @throws MigrateException if the heap walk fails (e.g., native library not loaded)
     */
    Set<Object> walkHeap() throws MigrateException;

    /**
     * Walk the heap and return objects of the specified classes only.
     *
     * <p>This method is more efficient than {@link #walkHeap()} when you know
     * which classes hold references to objects being migrated. Only objects
     * whose class is in the provided collection will be returned.
     *
     * @param classes the classes to filter for (null or empty returns empty set)
     * @return an identity-based set of objects matching the specified classes
     * @throws MigrateException if the heap walk fails (e.g., native library not loaded)
     */
    Set<Object> walkHeap(Collection<Class<?>> classes) throws MigrateException;
}
