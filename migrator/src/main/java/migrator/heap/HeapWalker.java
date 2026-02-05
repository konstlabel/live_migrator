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
     * Walk the entire heap and return all objects.
     * Use this for full heap patching.
     */
    Set<Object> walkHeap() throws MigrateException;

    /**
     * Walk the heap and return objects of the specified classes only.
     * More efficient than full heap walk when you know which classes to target.
     */
    Set<Object> walkHeap(Collection<Class<?>> classes) throws MigrateException;
}
