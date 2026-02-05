package migrator.heap;

import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

import migrator.exceptions.MigrateException;

/**
 * Native implementation of {@link HeapWalker} using JNI.
 *
 * <p>This implementation uses native methods to efficiently walk the JVM heap
 * and locate objects of specific types. It supports:
 * <ul>
 *   <li>Taking snapshots of objects by class type</li>
 *   <li>Resolving object references from snapshot tags</li>
 *   <li>Full heap walks returning all live objects</li>
 *   <li>Filtered heap walks for specific classes only</li>
 *   <li>Epoch advancement for tracking migration generations</li>
 * </ul>
 *
 * <p><strong>Note:</strong> Requires the native migrator library to be loaded.
 *
 * @see HeapWalker
 * @see HeapSnapshot
 */
public final class NativeHeapWalker implements HeapWalker {

    private static native byte[] nativeSnapshotBytes(String internalName);
    private static native Object nativeResolve(long tag);
    private native Object[] nativeWalkHeap();
    private native Object[] nativeWalkHeapFiltered(String[] internalClassNames);
    private static native void nativeAdvanceEpoch();

    @Override
    public HeapSnapshot snapshot(Class<?> targetClass) {
        String name = targetClass.getName().replace('.', '/');
        return HeapSnapshot.fromBytes(nativeSnapshotBytes(name));
    }

    @Override
    public Object resolve(long tag) {
        return nativeResolve(tag);
    }

    @Override
    public Set<Object> walkHeap() {
        Object[] objects = nativeWalkHeap();
        Set<Object> set = Collections.newSetFromMap(new IdentityHashMap<>());
        if (objects != null) {
            Collections.addAll(set, objects);
        }
        return set;
    }

    @Override
    public Set<Object> walkHeap(Collection<Class<?>> classes) throws MigrateException {
        if (classes == null || classes.isEmpty()) return Collections.emptySet();
        String[] names = classes.stream()
                                .filter(Objects::nonNull)
                                .map(c -> c.getName().replace('.', '/'))
                                .distinct()
                                .toArray(String[]::new);
        Object[] objs = nativeWalkHeapFiltered(names);
        Set<Object> set = Collections.newSetFromMap(new IdentityHashMap<>());
        if (objs != null) Collections.addAll(set, objs);
        return set;
    }
    
    /**
     * Advances the migration epoch counter.
     *
     * <p>This method should be called after a successful migration to mark
     * a new generation of objects. It helps the native layer distinguish
     * between pre-migration and post-migration objects.
     */
    public static void advanceEpoch() {
        nativeAdvanceEpoch();
    }
}
