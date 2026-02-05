package migrator.patch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.Reference;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Reflection-based {@link ReferencePatcher} implementation.
 *
 * <p>Features:
 * <ul>
 *   <li>Module-aware access handling (skips JDK internals)</li>
 *   <li>Identity-based visited set to avoid infinite recursion on cycles</li>
 *   <li>Safe access setup using trySetAccessible / setAccessible fallback</li>
 *   <li>Handles collections, arrays, Optional, Reference, ThreadLocal, etc.</li>
 *   <li>Creates replacement containers for immutable collections</li>
 * </ul>
 *
 * @see ForwardingTable
 */
public final class ReflectionReferencePatcher implements ReferencePatcher {

    private static final Logger log = LoggerFactory.getLogger(ReflectionReferencePatcher.class);

    private final ForwardingTable forwarding;

    public ReflectionReferencePatcher(ForwardingTable forwarding) {
        this.forwarding = Objects.requireNonNull(forwarding);
    }

    @Override
    public void patchObject(Object obj) {
        if (obj == null) return;
        // per-invocation visited set to prevent cycles
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        patchObjectRecursive(obj, visited);
    }

    @Override
    public void patchStaticFields(Class<?> clazz) {
        if (clazz == null) return;
        // skip JDK classes
        Module module = clazz.getModule();
        String moduleName = module != null ? module.getName() : null;
        if (moduleName != null && (moduleName.startsWith("java") || moduleName.startsWith("jdk"))) {
            return;
        }
        // visited set for deep patching static field contents
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        getAllStaticNonPrimitiveFields(clazz)
            .forEach(field -> patchStaticField(field, visited));
    }

    // ── Internal recursive implementation ───────────────────────────────────────────────

    private void patchObjectRecursive(Object obj, Set<Object> visited) {
        if (obj == null) return;

        // prevent cycles
        if (!visited.add(obj)) return;

        Class<?> cls = obj.getClass();

        if (cls.isArray()) {
            Class<?> componentType = cls.getComponentType();
            if (componentType.isPrimitive()) {
                return; // nothing to patch
            }
            patchArray(obj, visited);
            return;
        }

        // Handle JDK container types specially - recurse into their contents
        // but don't modify their internal fields
        Module module = cls.getModule();
        String moduleName = module != null ? module.getName() : null;
        boolean isJdkClass = moduleName != null &&
                (moduleName.startsWith("java") || moduleName.startsWith("jdk"));

        if (isJdkClass) {
            // Handle specific JDK containers by recursing into their contents
            patchJdkContainer(obj, visited);
            return;
        }

        getAllNonStaticNonPrimitiveFields(cls)
                .forEach(field -> patchField(obj, field, visited));
    }

    /**
     * Handle JDK container types by recursing into their contents.
     * We don't modify JDK internal fields, but we do patch/recurse their contained objects.
     */
    private void patchJdkContainer(Object obj, Set<Object> visited) {
        if (obj instanceof List<?> list) {
            patchList(list, visited);
        } else if (obj instanceof Map<?, ?> map) {
            patchMap(map, visited);
        } else if (obj instanceof Collection<?> collection) {
            patchCollection(collection, visited);
        } else if (obj instanceof Optional<?> optional) {
            patchOptional(optional, visited);
        } else if (obj instanceof Reference<?> ref) {
            patchReference(ref, visited);
        }
        // Other JDK types (ThreadLocal, etc.) - skip, they're handled via field patching
    }

    @SuppressWarnings("unchecked")
    private void patchList(List<?> list, Set<Object> visited) {
        List<Object> mutableList;
        try {
            mutableList = (List<Object>) list;
        } catch (ClassCastException e) {
            return;
        }

        for (int i = 0; i < mutableList.size(); i++) {
            Object val = mutableList.get(i);
            if (val == null) continue;

            Object replacement = forwarding.get(val);
            if (replacement != null) {
                try {
                    mutableList.set(i, replacement);
                } catch (Exception e) {
                    // best-effort: immutable list or other issue
                    log.debug("Failed to replace list element at index {}: {}", i, e.getMessage());
                }
            } else {
                patchObjectRecursive(val, visited);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void patchCollection(Collection<?> collection, Set<Object> visited) {
        // For non-List collections, we need to collect replacements and do bulk update
        List<Object> toRemove = new ArrayList<>();
        List<Object> toAdd = new ArrayList<>();

        for (Object val : collection) {
            if (val == null) continue;

            Object replacement = forwarding.get(val);
            if (replacement != null) {
                toRemove.add(val);
                toAdd.add(replacement);
            } else {
                patchObjectRecursive(val, visited);
            }
        }

        if (!toRemove.isEmpty()) {
            try {
                Collection<Object> mutableCollection = (Collection<Object>) collection;
                mutableCollection.removeAll(toRemove);
                mutableCollection.addAll(toAdd);
            } catch (Exception e) {
                log.debug("Failed to update collection: {}", e.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void patchMap(Map<?, ?> map, Set<Object> visited) {
        Map<Object, Object> mutableMap;
        try {
            mutableMap = (Map<Object, Object>) map;
        } catch (ClassCastException e) {
            return;
        }

        // Collect changes first to avoid ConcurrentModificationException
        List<Object> keysToRemove = new ArrayList<>();
        Map<Object, Object> entriesToAdd = new LinkedHashMap<>();

        for (Map.Entry<Object, Object> entry : mutableMap.entrySet()) {
            Object key = entry.getKey();
            Object val = entry.getValue();

            Object newKey = key != null ? forwarding.get(key) : null;
            Object newVal = val != null ? forwarding.get(val) : null;

            if (newKey != null || newVal != null) {
                keysToRemove.add(key);
                entriesToAdd.put(newKey != null ? newKey : key, newVal != null ? newVal : val);
            } else {
                // Recurse into key and value
                if (key != null) patchObjectRecursive(key, visited);
                if (val != null) patchObjectRecursive(val, visited);
            }
        }

        // Apply changes
        if (!keysToRemove.isEmpty()) {
            try {
                for (Object key : keysToRemove) {
                    mutableMap.remove(key);
                }
                mutableMap.putAll(entriesToAdd);
            } catch (Exception e) {
                log.debug("Failed to update map: {}", e.getMessage());
            }
        }
    }

    private void patchOptional(Optional<?> optional, Set<Object> visited) {
        if (optional.isEmpty()) return;

        Object val = optional.get();
        Object replacement = forwarding.get(val);

        if (replacement != null) {
            // Can't replace value in existing Optional, but we can recurse
            // The field holding this Optional should be replaced with a new Optional
            log.debug("Optional contains old object but Optional itself is immutable");
        } else {
            patchObjectRecursive(val, visited);
        }
    }

    private void patchReference(Reference<?> ref, Set<Object> visited) {
        Object val = ref.get();
        if (val == null) return;

        Object replacement = forwarding.get(val);
        if (replacement != null) {
            // WeakReference/SoftReference don't support changing the referent
            // The field holding this Reference should be replaced
            log.debug("Reference contains old object but Reference itself is immutable");
        } else {
            patchObjectRecursive(val, visited);
        }
    }

    /**
     * Try to create a replacement for immutable containers (Optional, Reference, etc.)
     * that contain old objects. For mutable containers like ThreadLocal, mutate in place.
     *
     * @param val the container value
     * @return a new container with the replacement object, or null if not applicable
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object tryCreateReplacementContainer(Object val) {
        if (val instanceof Optional<?> optional) {
            if (optional.isPresent()) {
                Object innerVal = optional.get();
                Object replacement = forwarding.get(innerVal);
                if (replacement != null) {
                    return Optional.of(replacement);
                }
            }
        } else if (val instanceof java.lang.ref.WeakReference<?> weakRef) {
            Object innerVal = weakRef.get();
            if (innerVal != null) {
                Object replacement = forwarding.get(innerVal);
                if (replacement != null) {
                    return new java.lang.ref.WeakReference<>(replacement);
                }
            }
        } else if (val instanceof java.lang.ref.SoftReference<?> softRef) {
            Object innerVal = softRef.get();
            if (innerVal != null) {
                Object replacement = forwarding.get(innerVal);
                if (replacement != null) {
                    return new java.lang.ref.SoftReference<>(replacement);
                }
            }
        } else if (val instanceof AtomicReference<?> atomicRef) {
            Object innerVal = atomicRef.get();
            if (innerVal != null) {
                Object replacement = forwarding.get(innerVal);
                if (replacement != null) {
                    // AtomicReference is mutable, update in place
                    ((AtomicReference) atomicRef).set(replacement);
                }
            }
            return null; // mutated in place
        } else if (val instanceof CompletableFuture<?> future) {
            if (future.isDone() && !future.isCompletedExceptionally()) {
                try {
                    Object innerVal = future.join();
                    if (innerVal != null) {
                        Object replacement = forwarding.get(innerVal);
                        if (replacement != null) {
                            return CompletableFuture.completedFuture(replacement);
                        }
                    }
                } catch (Exception e) {
                    log.debug("Failed to get CompletableFuture value: {}", e.getMessage());
                }
            }
        } else if (val instanceof ThreadLocal<?> threadLocal) {
            // ThreadLocal can be mutated via set()
            Object innerVal = threadLocal.get();
            if (innerVal != null) {
                Object replacement = forwarding.get(innerVal);
                if (replacement != null) {
                    try {
                        ((ThreadLocal) threadLocal).set(replacement);
                    } catch (Exception e) {
                        log.debug("Failed to update ThreadLocal: {}", e.getMessage());
                    }
                }
            }
            // Return null since we mutated in place, no replacement needed
        } else if (val instanceof Supplier<?> supplier) {
            // Try to get the value and create a replacement supplier
            try {
                Object innerVal = supplier.get();
                if (innerVal != null) {
                    Object replacement = forwarding.get(innerVal);
                    if (replacement != null) {
                        return (Supplier) () -> replacement;
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to get Supplier value: {}", e.getMessage());
            }
        } else if (val instanceof Map<?, ?> map) {
            // Only handle immutable maps - mutable ones are patched in place
            if (isImmutableCollection(map)) {
                return tryCreateReplacementMap(map);
            }
        } else if (val instanceof List<?> list) {
            // Only handle immutable lists - mutable ones are patched in place
            if (isImmutableCollection(list)) {
                return tryCreateReplacementList(list);
            }
        }
        return null;
    }

    private boolean isImmutableCollection(Object collection) {
        String className = collection.getClass().getName();
        return className.contains("ImmutableCollections")
            || className.contains("Unmodifiable")
            || className.contains("SingletonList")
            || className.contains("SingletonMap")
            || className.contains("EmptyList")
            || className.contains("EmptyMap");
    }

    private Object tryCreateReplacementMap(Map<?, ?> map) {
        boolean needsReplacement = false;
        Map<Object, Object> newMap = new LinkedHashMap<>();

        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Object key = entry.getKey();
            Object val = entry.getValue();

            Object newKey = key != null ? forwarding.get(key) : null;
            Object newVal = val != null ? forwarding.get(val) : null;

            // Recursively handle nested containers
            if (newVal == null && val != null) {
                Object nestedReplacement = tryCreateReplacementContainer(val);
                if (nestedReplacement != null) {
                    newVal = nestedReplacement;
                }
            }

            if (newKey != null || newVal != null) {
                needsReplacement = true;
                newMap.put(newKey != null ? newKey : key, newVal != null ? newVal : val);
            } else {
                newMap.put(key, val);
            }
        }

        if (needsReplacement) {
            return Map.copyOf(newMap);
        }
        return null;
    }

    private Object tryCreateReplacementList(List<?> list) {
        boolean needsReplacement = false;
        List<Object> newList = new ArrayList<>();

        for (Object val : list) {
            Object replacement = val != null ? forwarding.get(val) : null;

            // Recursively handle nested containers
            if (replacement == null && val != null) {
                Object nestedReplacement = tryCreateReplacementContainer(val);
                if (nestedReplacement != null) {
                    replacement = nestedReplacement;
                }
            }

            if (replacement != null) {
                needsReplacement = true;
                newList.add(replacement);
            } else {
                newList.add(val);
            }
        }

        if (needsReplacement) {
            return List.copyOf(newList);
        }
        return null;
    }

    private void patchArray(Object array, Set<Object> visited) {
        int len = Array.getLength(array);
        for (int i = 0; i < len; i++) {
            Object val = Array.get(array, i);
            if (val == null) continue;

            Object replacement = forwarding.get(val);
            if (replacement != null) {
                try {
                    Array.set(array, i, replacement);
                } catch (Exception e) {
                    // best-effort: ignore failures to set array element
                }
            } else {
                // recurse on contained object
                patchObjectRecursive(val, visited);
            }
        }
    }

    private void patchField(Object obj, Field field, Set<Object> visited) {
        try {
            // Skip fields declared in JDK modules
            Module declModule = field.getDeclaringClass().getModule();
            String declModuleName = declModule != null ? declModule.getName() : null;
            if (declModuleName != null && (declModuleName.startsWith("java") || declModuleName.startsWith("jdk"))) {
                return;
            }

            boolean accessible = ensureFieldAccessible(field, obj);
            if (!accessible) return;

            Object val = field.get(obj);
            if (val == null) return;

            Object replacement = forwarding.get(val);
            if (replacement != null) {
                field.set(obj, replacement);
            } else {
                // Check for immutable containers that need to be replaced entirely
                Object containerReplacement = tryCreateReplacementContainer(val);
                if (containerReplacement != null) {
                    field.set(obj, containerReplacement);
                } else {
                    patchObjectRecursive(val, visited);
                }
            }
        } catch (IllegalAccessException e) {
            // best-effort: log and continue
            log.debug("Failed to patch field {}: {}", field, e.getMessage());
        }
    }

    private void patchStaticField(Field field, Set<Object> visited) {
        try {
            Module declModule = field.getDeclaringClass().getModule();
            String declModuleName = declModule != null ? declModule.getName() : null;
            if (declModuleName != null && (declModuleName.startsWith("java") || declModuleName.startsWith("jdk"))) {
                return;
            }

            boolean accessible = ensureFieldAccessible(field, null);
            if (!accessible) return;

            Object val = field.get(null);
            if (val == null) return;

            // Check for immutable containers that need to be replaced entirely
            Object containerReplacement = tryCreateReplacementContainer(val);
            if (containerReplacement != null) {
                field.set(null, containerReplacement);
                return;
            }

            Object replacement = forwarding.get(val);
            if (replacement != null) {
                field.set(null, replacement);
            } else {
                patchObjectRecursive(val, visited);
            }
        } catch (IllegalAccessException e) {
            // best-effort: log and continue
            log.debug("Failed to patch static field {}: {}", field, e.getMessage());
        }
    }

    // Ensure access to field; try canAccess -> trySetAccessible -> setAccessible fallback.
    // targetInstance may be null for static fields.
    private boolean ensureFieldAccessible(Field field, Object targetInstance) {
        return canAccessDirectly(field, targetInstance)
            || tryModernAccessible(field)
            || tryLegacyAccessible(field);
    }

    private boolean canAccessDirectly(Field field, Object targetInstance) {
        try {
            return field.canAccess(targetInstance);
        } catch (NoSuchMethodError | UnsupportedOperationException e) {
            return false; // older JVM
        }
    }

    private boolean tryModernAccessible(Field field) {
        try {
            return field.trySetAccessible();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean tryLegacyAccessible(Field field) {
        try {
            field.setAccessible(true);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Field enumeration helpers ───────────────────────────────────────────────

    private Stream<Field> getAllNonStaticNonPrimitiveFields(Class<?> startClass) {
        return getAllFields(startClass)
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .filter(f -> !f.getType().isPrimitive());
    }

    private Stream<Field> getAllStaticNonPrimitiveFields(Class<?> clazz) {
        return getAllFields(clazz)
                .filter(f -> Modifier.isStatic(f.getModifiers()))
                .filter(f -> !f.getType().isPrimitive());
    }

    private Stream<Field> getAllFields(Class<?> startClass) {
        return Stream.iterate(startClass, Objects::nonNull, (Class<?> clazz) -> clazz.getSuperclass())
                    .flatMap(c -> Arrays.stream(c.getDeclaredFields()));
    }
}
