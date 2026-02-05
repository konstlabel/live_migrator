package migrator.engine;

import migrator.patch.ForwardingTable;
import migrator.patch.ReflectionReferencePatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReflectionReferencePatcher")
class ReflectionReferencePatcherTest {

    private ForwardingTable forwarding;
    private ReflectionReferencePatcher patcher;

    // Common interface for test classes
    interface Identifiable {
        int getId();
    }

    static class OldClass implements Identifiable {
        private final int id;
        OldClass(int id) { this.id = id; }
        @Override
        public int getId() { return id; }
    }

    static class NewClass implements Identifiable {
        private final int id;
        NewClass(int id) { this.id = id; }
        @Override
        public int getId() { return id; }
    }

    // Container with field typed as interface (allows replacement)
    static class ContainerWithField {
        Identifiable reference;
        ContainerWithField(Identifiable ref) { this.reference = ref; }
    }

    // Container with Object field (allows any replacement)
    static class ContainerWithObjectField {
        Object reference;
        ContainerWithObjectField(Object ref) { this.reference = ref; }
    }

    static class ContainerWithArray {
        Object[] array;
        ContainerWithArray(Object[] arr) { this.array = arr; }
    }

    static class ContainerWithList {
        List<Object> list;
        ContainerWithList(List<Object> list) { this.list = list; }
    }

    static class ContainerWithMap {
        Map<Object, Object> map;
        ContainerWithMap(Map<Object, Object> map) { this.map = map; }
    }

    static class ContainerWithOptional {
        Optional<Object> optional;
        ContainerWithOptional(Optional<Object> opt) { this.optional = opt; }
    }

    static class ContainerWithWeakRef {
        WeakReference<Object> weakRef;
        ContainerWithWeakRef(WeakReference<Object> ref) { this.weakRef = ref; }
    }

    @BeforeEach
    void setUp() {
        forwarding = new ForwardingTable();
        patcher = new ReflectionReferencePatcher(forwarding);
    }

    @Nested
    @DisplayName("patchObject with direct fields")
    class DirectFields {

        @Test
        @DisplayName("should replace direct field reference with interface type")
        void shouldReplaceDirectFieldReferenceWithInterfaceType() {
            OldClass old = new OldClass(1);
            NewClass replacement = new NewClass(1);
            forwarding.put(old, replacement);

            ContainerWithField container = new ContainerWithField(old);
            patcher.patchObject(container);

            assertThat(container.reference).isSameAs(replacement);
        }

        @Test
        @DisplayName("should replace direct field reference with Object type")
        void shouldReplaceDirectFieldReferenceWithObjectType() {
            OldClass old = new OldClass(1);
            NewClass replacement = new NewClass(1);
            forwarding.put(old, replacement);

            ContainerWithObjectField container = new ContainerWithObjectField(old);
            patcher.patchObject(container);

            assertThat(container.reference).isSameAs(replacement);
        }

        @Test
        @DisplayName("should not modify field if no replacement exists")
        void shouldNotModifyFieldIfNoReplacement() {
            OldClass old = new OldClass(1);
            ContainerWithField container = new ContainerWithField(old);

            patcher.patchObject(container);

            assertThat(container.reference).isSameAs(old);
        }

        @Test
        @DisplayName("should handle null fields")
        void shouldHandleNullFields() {
            ContainerWithField container = new ContainerWithField(null);

            // Should not throw
            patcher.patchObject(container);

            assertThat(container.reference).isNull();
        }
    }

    @Nested
    @DisplayName("patchObject with arrays")
    class Arrays {

        @Test
        @DisplayName("should replace array elements")
        void shouldReplaceArrayElements() {
            OldClass old = new OldClass(1);
            NewClass replacement = new NewClass(1);
            forwarding.put(old, replacement);

            Object[] array = new Object[] { old, "keep", null };
            ContainerWithArray container = new ContainerWithArray(array);

            patcher.patchObject(container);

            assertThat(array[0]).isSameAs(replacement);
            assertThat(array[1]).isEqualTo("keep");
            assertThat(array[2]).isNull();
        }

        @Test
        @DisplayName("should recursively patch nested objects in array")
        void shouldRecursivelyPatchNestedObjects() {
            OldClass old = new OldClass(1);
            NewClass replacement = new NewClass(1);
            forwarding.put(old, replacement);

            ContainerWithField nested = new ContainerWithField(old);
            Object[] array = new Object[] { nested };
            ContainerWithArray container = new ContainerWithArray(array);

            patcher.patchObject(container);

            assertThat(nested.reference).isSameAs(replacement);
        }
    }

    @Nested
    @DisplayName("patchObject with Lists")
    class Lists {

        @Test
        @DisplayName("should replace list elements")
        void shouldReplaceListElements() {
            OldClass old = new OldClass(1);
            NewClass replacement = new NewClass(1);
            forwarding.put(old, replacement);

            List<Object> list = new ArrayList<>();
            list.add(old);
            list.add("keep");
            ContainerWithList container = new ContainerWithList(list);

            patcher.patchObject(container);

            assertThat(list.get(0)).isSameAs(replacement);
            assertThat(list.get(1)).isEqualTo("keep");
        }

        @Test
        @DisplayName("should recursively patch objects inside list")
        void shouldRecursivelyPatchObjectsInsideList() {
            OldClass old = new OldClass(1);
            NewClass replacement = new NewClass(1);
            forwarding.put(old, replacement);

            // Use interface-typed field container
            ContainerWithField nested = new ContainerWithField(old);
            List<Object> list = new ArrayList<>();
            list.add(nested);
            ContainerWithList container = new ContainerWithList(list);

            patcher.patchObject(container);

            assertThat(nested.reference).isSameAs(replacement);
        }
    }

    @Nested
    @DisplayName("patchObject with Maps")
    class Maps {

        @Test
        @DisplayName("should replace map values")
        void shouldReplaceMapValues() {
            OldClass old = new OldClass(1);
            NewClass replacement = new NewClass(1);
            forwarding.put(old, replacement);

            Map<Object, Object> map = new HashMap<>();
            map.put("key", old);
            ContainerWithMap container = new ContainerWithMap(map);

            patcher.patchObject(container);

            assertThat(map.get("key")).isSameAs(replacement);
        }

        @Test
        @DisplayName("should replace map keys")
        void shouldReplaceMapKeys() {
            OldClass oldKey = new OldClass(1);
            NewClass newKey = new NewClass(1);
            forwarding.put(oldKey, newKey);

            Map<Object, Object> map = new HashMap<>();
            map.put(oldKey, "value");
            ContainerWithMap container = new ContainerWithMap(map);

            patcher.patchObject(container);

            assertThat(map.containsKey(newKey)).isTrue();
            assertThat(map.containsKey(oldKey)).isFalse();
            assertThat(map.get(newKey)).isEqualTo("value");
        }

        @Test
        @DisplayName("should replace both key and value")
        void shouldReplaceBothKeyAndValue() {
            OldClass oldKey = new OldClass(1);
            OldClass oldValue = new OldClass(2);
            NewClass newKey = new NewClass(1);
            NewClass newValue = new NewClass(2);
            forwarding.put(oldKey, newKey);
            forwarding.put(oldValue, newValue);

            Map<Object, Object> map = new HashMap<>();
            map.put(oldKey, oldValue);
            ContainerWithMap container = new ContainerWithMap(map);

            patcher.patchObject(container);

            assertThat(map.containsKey(newKey)).isTrue();
            assertThat(map.get(newKey)).isSameAs(newValue);
        }
    }

    @Nested
    @DisplayName("patchObject with Optional")
    class Optionals {

        @Test
        @DisplayName("should replace Optional content via field replacement")
        void shouldReplaceOptionalContentViaFieldReplacement() {
            OldClass old = new OldClass(1);
            NewClass replacement = new NewClass(1);
            forwarding.put(old, replacement);

            Optional<Object> optional = Optional.of(old);
            ContainerWithOptional container = new ContainerWithOptional(optional);

            patcher.patchObject(container);

            // Optional is immutable, so it should be replaced entirely
            assertThat(container.optional).isNotSameAs(optional);
            assertThat(container.optional.isPresent()).isTrue();
            assertThat(container.optional.get()).isSameAs(replacement);
        }

        @Test
        @DisplayName("should handle empty Optional")
        void shouldHandleEmptyOptional() {
            Optional<Object> optional = Optional.empty();
            ContainerWithOptional container = new ContainerWithOptional(optional);

            patcher.patchObject(container);

            assertThat(container.optional).isEmpty();
        }
    }

    @Nested
    @DisplayName("patchObject with WeakReference")
    class WeakReferences {

        @Test
        @DisplayName("should replace WeakReference content via field replacement")
        void shouldReplaceWeakReferenceViaFieldReplacement() {
            OldClass old = new OldClass(1);
            NewClass replacement = new NewClass(1);
            forwarding.put(old, replacement);

            WeakReference<Object> weakRef = new WeakReference<>(old);
            ContainerWithWeakRef container = new ContainerWithWeakRef(weakRef);

            patcher.patchObject(container);

            // WeakReference is replaced with new one
            assertThat(container.weakRef).isNotSameAs(weakRef);
            assertThat(container.weakRef.get()).isSameAs(replacement);
        }
    }

    @Nested
    @DisplayName("patchObject with collections (Set, Queue)")
    class OtherCollections {

        @Test
        @DisplayName("should replace Set elements")
        void shouldReplaceSetElements() {
            OldClass old = new OldClass(1);
            NewClass replacement = new NewClass(1);
            forwarding.put(old, replacement);

            Set<Object> set = new HashSet<>();
            set.add(old);
            set.add("keep");

            patcher.patchObject(set);

            assertThat(set).contains(replacement);
            assertThat(set).doesNotContain(old);
            assertThat(set).contains("keep");
        }
    }

    @Nested
    @DisplayName("cycle detection")
    class CycleDetection {

        static class SelfRef {
            SelfRef self;
            Object value;
        }

        @Test
        @DisplayName("should handle self-referencing objects")
        void shouldHandleSelfReferencingObjects() {
            SelfRef obj = new SelfRef();
            obj.self = obj;

            // Should not stack overflow
            patcher.patchObject(obj);

            assertThat(obj.self).isSameAs(obj);
        }

        @Test
        @DisplayName("should handle mutual references")
        void shouldHandleMutualReferences() {
            SelfRef a = new SelfRef();
            SelfRef b = new SelfRef();
            a.self = b;
            b.self = a;

            // Should not stack overflow
            patcher.patchObject(a);
        }
    }

    @Nested
    @DisplayName("patchStaticFields")
    class StaticFields {

        static class WithStaticField {
            // Use Object type to allow replacement
            static Object staticRef;
        }

        @Test
        @DisplayName("should patch static field with Object type")
        void shouldPatchStaticFieldWithObjectType() {
            OldClass old = new OldClass(1);
            NewClass replacement = new NewClass(1);
            WithStaticField.staticRef = old;
            forwarding.put(old, replacement);

            patcher.patchStaticFields(WithStaticField.class);

            assertThat(WithStaticField.staticRef).isSameAs(replacement);
        }

        @Test
        @DisplayName("should handle null static field")
        void shouldHandleNullStaticField() {
            WithStaticField.staticRef = null;

            // Should not throw
            patcher.patchStaticFields(WithStaticField.class);

            assertThat(WithStaticField.staticRef).isNull();
        }
    }

    @Nested
    @DisplayName("edge cases")
    class EdgeCases {

        @Test
        @DisplayName("should handle null input")
        void shouldHandleNullInput() {
            // Should not throw
            patcher.patchObject(null);
        }

        @Test
        @DisplayName("should handle primitive arrays")
        void shouldHandlePrimitiveArrays() {
            int[] primitiveArray = new int[] { 1, 2, 3 };

            // Should not throw
            patcher.patchObject(primitiveArray);
        }

        @Test
        @DisplayName("should handle empty collections")
        void shouldHandleEmptyCollections() {
            List<Object> emptyList = new ArrayList<>();
            Map<Object, Object> emptyMap = new HashMap<>();
            Set<Object> emptySet = new HashSet<>();

            // Should not throw
            patcher.patchObject(emptyList);
            patcher.patchObject(emptyMap);
            patcher.patchObject(emptySet);
        }

        @Test
        @DisplayName("should handle null class in patchStaticFields")
        void shouldHandleNullClassInPatchStaticFields() {
            // Should not throw
            patcher.patchStaticFields(null);
        }
    }
}
