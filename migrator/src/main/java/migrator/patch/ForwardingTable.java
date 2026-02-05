package migrator.patch;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/**
 * Maps old objects to their migrated counterparts using identity-based weak references.
 *
 * <p>This table is used during migration to track which old objects have been
 * migrated and what their new counterparts are. Uses weak references to allow
 * garbage collection of old objects.
 *
 * <p>Key features:
 * <ul>
 *   <li>Identity-based comparison (not equals/hashCode)</li>
 *   <li>Weak references allow GC of old objects</li>
 *   <li>Automatic cleanup of stale entries</li>
 * </ul>
 *
 * @see ReferencePatcher
 */
public final class ForwardingTable {

    private static final class IdentityWeakRef extends WeakReference<Object> {
        private final int hash;

        IdentityWeakRef(Object referent, ReferenceQueue<Object> q) {
            super(referent, q);
            this.hash = System.identityHashCode(referent);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof IdentityWeakRef other)) return false;
            Object a = this.get();
            Object b = other.get();
            // If either referent has been GC'd, they should not match
            if (a == null || b == null) {
                return false;
            }
            return a == b;
        }
    }

    private final ReferenceQueue<Object> queue = new ReferenceQueue<>();
    private final Map<IdentityWeakRef, Object> map = new HashMap<>();

    /**
     * Register a mapping from old object to new object.
     *
     * @param oldObj the original object
     * @param newObj the migrated object
     */
    public void put(Object oldObj, Object newObj) {
        cleanup();
        map.put(new IdentityWeakRef(oldObj, queue), newObj);
    }

    /**
     * Get the migrated object for the given old object.
     *
     * @param oldObj the original object
     * @return the migrated object, or null if not found
     */
    public Object get(Object oldObj) {
        cleanup();
        return map.get(new IdentityWeakRef(oldObj, null));
    }

    /**
     * Check if the table contains a mapping for the given object.
     *
     * @param oldObj the original object
     * @return true if a mapping exists
     */
    public boolean contains(Object oldObj) {
        cleanup();
        return map.containsKey(new IdentityWeakRef(oldObj, null));
    }

    /**
     * Remove the mapping for the given object.
     *
     * @param oldObj the original object
     */
    public void remove(Object oldObj) {
        cleanup();
        map.remove(new IdentityWeakRef(oldObj, null));
    }

    /** Clear all mappings. */
    public void clear() {
        map.clear();
    }

    private void cleanup() {
        IdentityWeakRef ref;
        while ((ref = (IdentityWeakRef) queue.poll()) != null) {
            map.remove(ref);
        }
    }
}
