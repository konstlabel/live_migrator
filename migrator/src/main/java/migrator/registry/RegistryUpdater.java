package migrator.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import migrator.patch.ForwardingTable;
import migrator.patch.ReferencePatcher;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentMap;

/**
 * Updates fields annotated with {@link UpdateRegistry} during migration.
 *
 * <p>This class is responsible for finding and updating "registry" or "cache"
 * fields that hold references to migrated objects. It supports:
 * <ul>
 *   <li>{@link java.util.Map} - keys and/or values can be replaced</li>
 *   <li>{@link java.util.Collection} - elements can be replaced</li>
 *   <li>Object arrays</li>
 *   <li>Custom registry types (via reflection and {@link RegistryAware})</li>
 *   <li>Generic containers with type parameters</li>
 * </ul>
 *
 * <p>The updater uses a {@link ForwardingTable} to look up migrated objects
 * and a {@link ReferencePatcher} for deep patching of nested structures.
 *
 * <h2>Usage:</h2>
 * <pre>
 * RegistryUpdater updater = new RegistryUpdater(forwarding, referencePatcher);
 * updater.updateAnnotatedRegistries(classesToScan, heapObjects);
 * </pre>
 *
 * @see UpdateRegistry
 * @see RegistryAware
 * @see ForwardingTable
 */
public final class RegistryUpdater {

    private static final Logger log = LoggerFactory.getLogger(RegistryUpdater.class);

    private final ForwardingTable forwarding;
    private final ReferencePatcher referencePatcher;

    /**
     * Creates a new registry updater.
     *
     * @param forwarding the forwarding table mapping old objects to new
     * @param referencePatcher the reference patcher for deep patching
     */
    public RegistryUpdater(ForwardingTable forwarding, ReferencePatcher referencePatcher) {
        this.forwarding = Objects.requireNonNull(forwarding);
        this.referencePatcher = Objects.requireNonNull(referencePatcher);
    }

    /**
     * Scans the specified classes for fields with @UpdateRegistry and patches them.
     * Typically, pass the new version classes (target classes) here.
     */
    public void updateAnnotatedRegistries(
        Collection<Class<?>> classesToScan,
        Collection<Object> heapObjects) {

        for (Class<?> cls : classesToScan) {
            for (Field f : cls.getDeclaredFields()) {
                UpdateRegistry ann = f.getAnnotation(UpdateRegistry.class);
                if (ann == null) continue;

                if (Modifier.isStatic(f.getModifiers())) {
                    patchStaticRegistryField(cls, f, ann);
                } else {
                    patchInstanceRegistryField(cls, f, ann, heapObjects);
                }
            }
        }
    }

    /**
     * Updates a generic container that holds elements implementing a common interface.
     *
     * <p>This method inspects the container and replaces all elements that are instances
     * of the specified interface type with their migrated versions from the forwarding table.
     *
     * <p>Supported container types:
     * <ul>
     *   <li>{@link Collection} - List, Set, Queue, etc.</li>
     *   <li>{@link Map} - updates values that match the interface type</li>
     *   <li>Arrays</li>
     *   <li>Custom containers with iterable fields</li>
     * </ul>
     *
     * <h2>Example:</h2>
     * <pre>
     * // Container with generic type parameter
     * List&lt;User&gt; users = ...;
     * registryUpdater.updateGenericContainer(users, User.class);
     * </pre>
     *
     * @param container the generic container holding elements
     * @param interfaceType the common interface type of elements to update
     */
    public void updateGenericContainer(Object container, Class<?> interfaceType) {
        if (container == null || interfaceType == null) {
            return;
        }

        log.debug("Updating generic container {} with interface type {}",
                  container.getClass().getName(), interfaceType.getName());

        if (container instanceof Map<?, ?> map) {
            updateGenericMap(map, interfaceType);
        } else if (container instanceof Collection<?> collection) {
            updateGenericCollection(collection, interfaceType);
        } else if (container.getClass().isArray()) {
            updateGenericArray(container, interfaceType);
        } else {
            // Custom container - try to find and update iterable/collection fields
            updateCustomGenericContainer(container, interfaceType);
        }
    }

    /**
     * Updates multiple generic containers with the same interface type.
     *
     * @param containers the containers to update
     * @param interfaceType the common interface type of elements to update
     */
    public void updateGenericContainers(Collection<?> containers, Class<?> interfaceType) {
        if (containers == null || interfaceType == null) {
            return;
        }

        for (Object container : containers) {
            updateGenericContainer(container, interfaceType);
        }
    }

    /**
     * Scans classes for fields that are generic types with a common interface type parameter
     * and updates them.
     *
     * <p>This method inspects all fields of the specified classes, looking for generic types
     * like {@code List<User>}, {@code Map<String, User>}, or {@code CustomContainer<User>}
     * where the type parameter matches one of the specified interface types.
     *
     * <h2>Example:</h2>
     * <pre>
     * class UserService {
     *     private List&lt;User&gt; users;           // Will be updated
     *     private Map&lt;String, User&gt; cache;    // Will be updated
     *     private CustomRegistry&lt;User&gt; reg;   // Will be updated
     * }
     *
     * registryUpdater.updateGenericFieldsInClasses(
     *     Set.of(UserService.class),
     *     heapObjects,
     *     Set.of(User.class)
     * );
     * </pre>
     *
     * @param classesToScan classes to scan for generic fields
     * @param heapObjects heap objects to search for instances of the scanned classes
     * @param interfaceTypes the interface types to look for in generic type parameters
     */
    public void updateGenericFieldsInClasses(
            Collection<Class<?>> classesToScan,
            Collection<Object> heapObjects,
            Set<Class<?>> interfaceTypes) {

        if (classesToScan == null || heapObjects == null || interfaceTypes == null || interfaceTypes.isEmpty()) {
            return;
        }

        for (Class<?> cls : classesToScan) {
            updateGenericFieldsInClass(cls, heapObjects, interfaceTypes);
        }
    }

    /**
     * Scans a single class for generic fields and updates them.
     */
    private void updateGenericFieldsInClass(
            Class<?> cls,
            Collection<Object> heapObjects,
            Set<Class<?>> interfaceTypes) {

        for (Field field : cls.getDeclaredFields()) {
            Type genericType = field.getGenericType();

            // Extract interface type from generic type parameter
            Class<?> matchedInterface = extractMatchingInterfaceType(genericType, interfaceTypes);
            if (matchedInterface == null) {
                continue;
            }

            log.debug("Found generic field {}.{} with interface type {}",
                      cls.getSimpleName(), field.getName(), matchedInterface.getSimpleName());

            if (Modifier.isStatic(field.getModifiers())) {
                updateStaticGenericField(cls, field, matchedInterface);
            } else {
                updateInstanceGenericField(cls, field, heapObjects, matchedInterface);
            }
        }
    }

    /**
     * Extracts a matching interface type from a generic type's type parameters.
     *
     * @param genericType the generic type to inspect
     * @param interfaceTypes the set of interface types to match against
     * @return the matched interface type, or null if no match found
     */
    private Class<?> extractMatchingInterfaceType(Type genericType, Set<Class<?>> interfaceTypes) {
        if (!(genericType instanceof ParameterizedType paramType)) {
            return null;
        }

        Type[] typeArgs = paramType.getActualTypeArguments();
        for (Type typeArg : typeArgs) {
            Class<?> typeArgClass = resolveTypeArgToClass(typeArg);
            if (typeArgClass != null) {
                // Check if this type arg matches any of the interface types
                for (Class<?> interfaceType : interfaceTypes) {
                    if (interfaceType.isAssignableFrom(typeArgClass) || typeArgClass.isAssignableFrom(interfaceType)) {
                        return interfaceType;
                    }
                }
            }
        }

        return null;
    }

    /**
     * Resolves a Type to its Class representation.
     */
    private Class<?> resolveTypeArgToClass(Type type) {
        if (type instanceof Class<?> cls) {
            return cls;
        }
        if (type instanceof ParameterizedType paramType) {
            Type rawType = paramType.getRawType();
            if (rawType instanceof Class<?> cls) {
                return cls;
            }
        }
        if (type instanceof WildcardType wildcardType) {
            Type[] upperBounds = wildcardType.getUpperBounds();
            if (upperBounds.length > 0 && upperBounds[0] instanceof Class<?> cls) {
                return cls;
            }
        }
        if (type instanceof TypeVariable<?> typeVar) {
            Type[] bounds = typeVar.getBounds();
            if (bounds.length > 0 && bounds[0] instanceof Class<?> cls) {
                return cls;
            }
        }
        return null;
    }

    /**
     * Updates a static generic field.
     */
    private void updateStaticGenericField(Class<?> owner, Field field, Class<?> interfaceType) {
        try {
            field.setAccessible(true);
            Object container = field.get(null);
            if (container != null) {
                updateGenericContainer(container, interfaceType);
            }
        } catch (Exception e) {
            log.warn("Failed to update static generic field {}.{}: {}",
                     owner.getName(), field.getName(), e.getMessage());
        }
    }

    /**
     * Updates instance generic fields across all heap objects of the declaring class.
     */
    private void updateInstanceGenericField(
            Class<?> declaringClass,
            Field field,
            Collection<Object> heapObjects,
            Class<?> interfaceType) {

        field.setAccessible(true);

        for (Object obj : heapObjects) {
            if (obj == null || !declaringClass.isInstance(obj)) {
                continue;
            }

            try {
                Object container = field.get(obj);
                if (container != null) {
                    updateGenericContainer(container, interfaceType);
                }
            } catch (Exception e) {
                log.warn("Failed to update instance generic field {}.{} on object: {}",
                         declaringClass.getName(), field.getName(), e.getMessage());
            }
        }
    }

    private void updateGenericMap(Map<?, ?> map, Class<?> interfaceType) {
        if (map == null || map.isEmpty()) {
            return;
        }

        List<Map.Entry<Object, Object>> snapshot = createSnapshot(map);
        boolean isConcurrent = map instanceof ConcurrentMap;

        for (Map.Entry<Object, Object> entry : snapshot) {
            Object key = entry.getKey();
            Object value = entry.getValue();

            Object newKey = null;
            Object newValue = null;

            // Check if key implements the interface
            if (interfaceType.isInstance(key)) {
                newKey = forwarding.get(key);
            }

            // Check if value implements the interface
            if (interfaceType.isInstance(value)) {
                newValue = forwarding.get(value);
            }

            if (newKey != null || newValue != null) {
                try {
                    if (isConcurrent) {
                        @SuppressWarnings("unchecked")
                        ConcurrentMap<Object, Object> cmap = (ConcurrentMap<Object, Object>) map;
                        patchConcurrent(cmap, key, value, newKey, newValue);
                    } else {
                        @SuppressWarnings("unchecked")
                        Map<Object, Object> mmap = (Map<Object, Object>) map;
                        patchRegular(mmap, key, newKey, value, newValue);
                    }
                } catch (Exception e) {
                    log.warn("Failed to update map entry: {}", e.getMessage());
                }
            }
        }
    }

    private void updateGenericCollection(Collection<?> collection, Class<?> interfaceType) {
        if (collection == null || collection.isEmpty()) {
            return;
        }

        if (collection instanceof List<?> list) {
            updateGenericList(list, interfaceType);
        } else {
            updateGenericSet(collection, interfaceType);
        }
    }

    @SuppressWarnings("unchecked")
    private void updateGenericList(List<?> list, Class<?> interfaceType) {
        List<Object> mutableList = (List<Object>) list;

        for (int i = 0; i < mutableList.size(); i++) {
            Object element = mutableList.get(i);

            if (interfaceType.isInstance(element)) {
                Object replacement = forwarding.get(element);
                if (replacement != null && replacement != element) {
                    try {
                        mutableList.set(i, replacement);
                        log.trace("Replaced element at index {} with migrated instance", i);
                    } catch (Exception e) {
                        log.warn("Failed to replace element at index {}: {}", i, e.getMessage());
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void updateGenericSet(Collection<?> collection, Class<?> interfaceType) {
        Collection<Object> mutableCollection = (Collection<Object>) collection;

        List<Object> toRemove = new ArrayList<>();
        List<Object> toAdd = new ArrayList<>();

        for (Object element : mutableCollection) {
            if (interfaceType.isInstance(element)) {
                Object replacement = forwarding.get(element);
                if (replacement != null && replacement != element) {
                    toRemove.add(element);
                    toAdd.add(replacement);
                }
            }
        }

        if (!toRemove.isEmpty()) {
            try {
                mutableCollection.removeAll(toRemove);
                mutableCollection.addAll(toAdd);
                log.trace("Replaced {} elements in collection", toRemove.size());
            } catch (Exception e) {
                log.warn("Failed to update collection: {}", e.getMessage());
            }
        }
    }

    private void updateGenericArray(Object array, Class<?> interfaceType) {
        if (array == null) {
            return;
        }

        int length = Array.getLength(array);
        for (int i = 0; i < length; i++) {
            Object element = Array.get(array, i);

            if (interfaceType.isInstance(element)) {
                Object replacement = forwarding.get(element);
                if (replacement != null && replacement != element) {
                    try {
                        Array.set(array, i, replacement);
                        log.trace("Replaced array element at index {}", i);
                    } catch (Exception e) {
                        log.warn("Failed to replace array element at index {}: {}", i, e.getMessage());
                    }
                }
            }
        }
    }

    private void updateCustomGenericContainer(Object container, Class<?> interfaceType) {
        Class<?> containerClass = container.getClass();

        // Try to find and update fields that are collections/maps/arrays
        for (Field field : containerClass.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }

            try {
                field.setAccessible(true);
                Object fieldValue = field.get(container);

                if (fieldValue == null) {
                    continue;
                }

                // Check if field itself implements the interface
                if (interfaceType.isInstance(fieldValue)) {
                    Object replacement = forwarding.get(fieldValue);
                    if (replacement != null && replacement != fieldValue) {
                        field.set(container, replacement);
                        log.trace("Replaced field {} with migrated instance", field.getName());
                        continue;
                    }
                }

                // Recursively update container-like fields
                if (fieldValue instanceof Collection || fieldValue instanceof Map || fieldValue.getClass().isArray()) {
                    updateGenericContainer(fieldValue, interfaceType);
                }
            } catch (Exception e) {
                log.warn("Failed to update field {}: {}", field.getName(), e.getMessage());
            }
        }

        // Also check if container implements Iterable
        if (container instanceof Iterable<?> iterable && !(container instanceof Collection)) {
            updateGenericIterable(iterable, interfaceType);
        }
    }

    private void updateGenericIterable(Iterable<?> iterable, Class<?> interfaceType) {
        // For generic iterables, we can only patch the objects themselves
        // since we can't modify the iterable structure without knowing its type
        for (Object element : iterable) {
            if (interfaceType.isInstance(element)) {
                // Try to patch the object's internal references
                referencePatcher.patchObject(element);
            }
        }
    }

    private void patchStaticRegistryField(Class<?> owner, Field field, UpdateRegistry ann) {
        try {
            field.setAccessible(true);
            Object registry = field.get(null);
            if (registry == null) return;

            // If the static reference itself points to an old object, replace it
            Object directReplacement = forwarding.get(registry);
            if (directReplacement != null) {
                field.set(null, directReplacement);
                registry = directReplacement;
            }

            // Process by type
            if (registry instanceof Map) {
                patchMap((Map<?, ?>) registry, ann.replaceKeys(), ann.replaceValues());
            } else if (registry instanceof Collection) {
                patchCollection((Collection<?>) registry);
            } else if (registry.getClass().isArray()) {
                patchArray(registry);
            } else {
                // Best-effort for custom registries:
                // 1) Try to recursively patch internals (deep)
                // 2) If replace/put/remove methods exist, try to use them
                if (ann.deep()) {
                    referencePatcher.patchObject(registry);
                }
                if (ann.reflective()) {
                    tryBestReflectiveRegistryOps(registry, ann);
                }
            }

            if (registry instanceof RegistryAware aware)
                safelyNotifyRegistryUpdated(aware, owner, field.getName());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Failed to read static field " + owner.getName() + "#" + field.getName(), e);
        }
    }

    private void safelyNotifyRegistryUpdated(RegistryAware aware, Class<?> owner, String fieldName) {
        try {
            aware.onRegistryUpdated();
        } catch (Exception e) {
            log.warn(
                "RegistryAware.onRegistryUpdated failed for {}#{}",
                owner.getName(),
                fieldName,
                e
            );
        }
    }

    private void patchInstanceRegistryField(
        Class<?> declaringClass,
        Field field,
        UpdateRegistry ann,
        Collection<Object> heapObjects) {

        field.setAccessible(true);

        for (Object obj : heapObjects) {
            if (obj == null || !declaringClass.isInstance(obj)) {
                continue;
            }

            try {
                Object value = field.get(obj);
                if (value != null) {
                    Object replacement = forwarding.get(value);
                    if (replacement != null && replacement != value) {
                        field.set(obj, replacement);
                    } else {
                        patchRegistryValue(value, ann);
                    }
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(
                        "Failed to patch instance registry field " + field, e
                );
            }
        }
    }

    private void patchRegistryValue(Object value, UpdateRegistry ann) {
        if (value instanceof Map) {
            patchMap(
                    (Map<?, ?>) value,
                    ann.replaceKeys(),
                    ann.replaceValues()
            );
        } else if (value instanceof Collection) {
            patchCollection((Collection<?>) value);
        } else {
            Object replacement = forwarding.get(value);
            if (!(replacement != null && replacement != value))
                referencePatcher.patchObject(value);
        }
    }

    // ---------------- Map ----------------

    private void patchMap(Map<?, ?> rawMap, boolean replaceKeys, boolean replaceValues) {
        if (rawMap == null || rawMap.isEmpty()) {
            return;
        }

        List<Map.Entry<Object, Object>> snapshot = createSnapshot(rawMap);

        Map<Object, Object> mutableMap = new HashMap<>();
        for (Map.Entry<?, ?> e : rawMap.entrySet()) {
            mutableMap.put(e.getKey(), e.getValue());
        }

        boolean isConcurrent = rawMap instanceof ConcurrentMap;

        patchEntries(snapshot, mutableMap, isConcurrent, replaceKeys, replaceValues);
        deepPatchValues(mutableMap);
    }

    private List<Map.Entry<Object, Object>> createSnapshot(Map<?, ?> map) {
        List<Map.Entry<Object, Object>> entries = new ArrayList<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            entries.add(new AbstractMap.SimpleEntry<>(e.getKey(), e.getValue()));
        }
        return entries;
    }

    private void patchEntries(
        List<Map.Entry<Object, Object>> entries,
        Map<Object, Object> map,
        boolean isConcurrent,
        boolean replaceKeys,
        boolean replaceValues) {
        for (Map.Entry<Object, Object> entry : entries) {
            Object oldKey = entry.getKey();
            Object oldVal = entry.getValue();

            Object newKey = replaceKeys ? forwarding.get(oldKey) : null;
            Object newVal = replaceValues ? forwarding.get(oldVal) : null;

            if (newKey == null && newVal == null) {
                continue;
            }

            try {
                if (isConcurrent) {
                    patchConcurrent((ConcurrentMap<Object, Object>) map, oldKey, oldVal, newKey, newVal);
                } else {
                    patchRegular(map, oldKey, newKey, oldVal, newVal);
                }
            } catch (Exception e) {
                tryFallbackMapReplace(map, oldKey, newKey, oldVal, newVal);
            }
        }
    }

    private void patchConcurrent(ConcurrentMap<Object, Object> cmap,
                             Object oldKey, Object oldVal,
                             Object newKey, Object newVal) {
        boolean keyChanged = newKey != null && newKey != oldKey;
        boolean valChanged = newVal != null && newVal != oldVal;

        if (!keyChanged && valChanged) {
            cmap.replace(oldKey, oldVal, newVal);
        } else {
            cmap.remove(oldKey, oldVal);
            Object putKey = newKey != null ? newKey : oldKey;
            Object putVal = newVal != null ? newVal : oldVal;
            cmap.put(putKey, putVal);
        }
    }

    private void patchRegular(Map<Object, Object> map,
                            Object oldKey, Object newKey,
                            Object oldVal, Object newVal) {
        map.remove(oldKey);

        Object putKey = newKey != null ? newKey : oldKey;
        Object putVal = newVal != null ? newVal : oldVal;
        map.put(putKey, putVal);
    }

    private void deepPatchValues(Map<?, ?> map) {
        for (Object value : map.values()) {
            referencePatcher.patchObject(value);
        }
    }

    private void tryFallbackMapReplace(
        Map<Object, Object> map, 
        Object oldKey, 
        Object newKey, 
        Object oldVal, 
        Object newVal) {
        try {
            List<Map.Entry<Object, Object>> toReinsert = new ArrayList<>();

            for (Map.Entry<Object, Object> entry : map.entrySet()) {
                if (entry.getKey() == oldKey) {
                    toReinsert.add(new AbstractMap.SimpleEntry<>(
                        newKey != null ? newKey : oldKey,
                        newVal != null ? newVal : oldVal
                    ));
                }
            }

            map.entrySet().removeIf(e -> e.getKey() == oldKey);
            map.put(newKey != null ? newKey : oldKey,
                    newVal != null ? newVal : oldVal);


            for (Map.Entry<Object, Object> e : toReinsert) {
                map.put(e.getKey(), e.getValue());
            }
        } catch (Exception ignore) {
            // best-effort
        }
    }

    // ---------------- Collection ----------------

    private void patchCollection(Collection<?> rawCollection) {
        if (rawCollection == null || rawCollection.isEmpty()) {
            return;
        }

        Collection<Object> collection = asObjectCollection(rawCollection);
        if (collection == null) {
            return;
        }

        if (collection instanceof List) {
            List<Object> list = (List<Object>) collection;
            patchListInPlace(list);
        } else {
            patchGenericCollection(collection);
        }
    }

    private Collection<Object> asObjectCollection(Collection<?> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }

        Collection<Object> result = new ArrayList<>(raw.size());
        for (Object o : raw) {
            result.add(o); // safe, everything casts to Object
        }
        return result;
    }

    private void patchListInPlace(List<Object> list) {
        for (int i = 0; i < list.size(); i++) {
            Object value = list.get(i);
            Object replacement = forwarding.get(value);

            if (replacement != null && replacement != value) {
                safelyReplaceAtIndex(list, i, replacement);
            } else {
                referencePatcher.patchObject(value);
            }
        }
    }

    private void safelyReplaceAtIndex(List<Object> list, int index, Object newValue) {
        try {
            list.set(index, newValue);
        } catch (Exception e) {
            // fallback: remove + insert (works even for CopyOnWriteArrayList)
            try {
                list.remove(index);
                list.add(index, newValue);
            } catch (Exception ignored) {
                // best-effort: silently ignore
            }
        }
    }

    private void patchGenericCollection(Collection<Object> col) {
        List<Object> toRemove = new ArrayList<>();
        List<Object> toAdd = new ArrayList<>();

        // First pass: collect changes
        for (Object value : col) {
            Object replacement = forwarding.get(value);
            if (replacement != null && replacement != value) {
                toRemove.add(value);
                toAdd.add(replacement);
            } else {
                referencePatcher.patchObject(value);
            }
        }

        // Apply changes as safely as possible
        safelyBulkReplace(col, toRemove, toAdd);
    }

    private void safelyBulkReplace(Collection<Object> col, List<Object> toRemove, List<Object> toAdd) {
        if (toRemove.isEmpty()) {
            return;
        }

        try {
            col.removeAll(toRemove);
            col.addAll(toAdd);
        } catch (Exception e) {
            // Safest fallback for most collections
            try {
                Iterator<Object> iterator = col.iterator();
                while (iterator.hasNext()) {
                    Object current = iterator.next();
                    if (toRemove.contains(current)) {
                        iterator.remove();
                    }
                }
                col.addAll(toAdd);
            } catch (Exception ignored) {
                // best-effort
            }
        }
    }

    // ---------------- Array ----------------

    private void patchArray(Object array) {
        if (array == null) {
            return;
        }

        int len = Array.getLength(array);
        for (int i = 0; i < len; i++) {
            Object value = Array.get(array, i);
            Object replacement = forwarding.get(value);

            if (replacement != null && replacement != value) {
                try {
                    Array.set(array, i, replacement);
                } catch (Exception e) {
                    // best-effort
                }
            } else {
                referencePatcher.patchObject(value);
            }
        }
    }

    // ---------------- Reflective ops for custom registries ----------------

    private void tryBestReflectiveRegistryOps(Object registry, UpdateRegistry ann) {
        if (registry == null || !ann.reflective()) {
            return;
        }

        Class<?> clazz = registry.getClass();
        if (!hasAnyModifyingMethod(clazz)) {
            return;
        }

        patchPublicCollectionLikeGetters(registry, clazz, ann);
    }

    private boolean hasAnyModifyingMethod(Class<?> clazz) {
        return findMethod(clazz, "replace", Object.class, Object.class) != null ||
            findMethod(clazz, "put", Object.class, Object.class) != null ||
            findMethod(clazz, "remove", Object.class) != null;
    }

    private void patchPublicCollectionLikeGetters(
        Object registry,
        Class<?> clazz,
        UpdateRegistry ann) {

        for (Method method : clazz.getDeclaredMethods()) {
            if (isSuitableGetter(method)) {
                Object value = safelyInvokeGetter(registry, method);
                if (value != null) {
                    patchValueIfSupported(value, ann);
                }
            }
        }
    }


    private boolean isSuitableGetter(Method m) {
        if (Modifier.isStatic(m.getModifiers())) {
            return false;
        }
        if (m.getParameterCount() != 0) {
            return false;
        }

        Class<?> returnType = m.getReturnType();
        return isCollectionLike(returnType);
    }

    private boolean isCollectionLike(Class<?> type) {
        return Map.class.isAssignableFrom(type) ||
            Collection.class.isAssignableFrom(type) ||
            type.isArray();
    }

    private Object safelyInvokeGetter(Object registry, Method method) {
        try {
            method.setAccessible(true);
            return method.invoke(registry);
        } catch (Exception e) {
            // best-effort: failed to invoke getter, skip
            return null;
        }
    }

    private void patchValueIfSupported(Object value, UpdateRegistry ann) {
        if (value == null) {
            return;
        }

        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            patchMap(map, ann.replaceKeys(), ann.replaceValues());
        }
        else if (value instanceof Collection) {
            Collection<?> col = (Collection<?>) value;
            patchCollection(col);
        }
        else if (value.getClass().isArray()) {
            patchArray(value);
        }
        else if (ann.deep()) {
            referencePatcher.patchObject(value);
        }
    }


    private Method findMethod(Class<?> rc, String name, Class<?>... params) {
        try {
            Method m = rc.getMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            // try declared
            try {
                Method m2 = rc.getDeclaredMethod(name, params);
                m2.setAccessible(true);
                return m2;
            } catch (NoSuchMethodException ex) {
                return null;
            }
        }
    }
}
