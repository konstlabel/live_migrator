package migrator.heap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HeapSnapshot")
class HeapSnapshotTest {

    @Nested
    @DisplayName("constructor")
    class Constructor {

        @Test
        @DisplayName("should create snapshot with entries")
        void shouldCreateSnapshotWithEntries() {
            List<HeapSnapshot.Entry> entries = List.of(
                    new HeapSnapshot.Entry(1L, "com.example.Foo"),
                    new HeapSnapshot.Entry(2L, "com.example.Bar")
            );

            HeapSnapshot snapshot = new HeapSnapshot(entries);

            assertThat(snapshot.entries()).hasSize(2);
            assertThat(snapshot.entries().get(0).tag()).isEqualTo(1L);
            assertThat(snapshot.entries().get(0).className()).isEqualTo("com.example.Foo");
        }

        @Test
        @DisplayName("should create immutable copy of entries")
        void shouldCreateImmutableCopyOfEntries() {
            List<HeapSnapshot.Entry> entries = new java.util.ArrayList<>();
            entries.add(new HeapSnapshot.Entry(1L, "com.example.Foo"));

            HeapSnapshot snapshot = new HeapSnapshot(entries);
            entries.add(new HeapSnapshot.Entry(2L, "com.example.Bar"));

            assertThat(snapshot.entries()).hasSize(1);
        }

        @Test
        @DisplayName("should return immutable entries list")
        void shouldReturnImmutableEntriesList() {
            HeapSnapshot snapshot = new HeapSnapshot(List.of(
                    new HeapSnapshot.Entry(1L, "com.example.Foo")
            ));

            List<HeapSnapshot.Entry> entries = snapshot.entries();

            assertThat(entries).isUnmodifiable();
        }
    }

    @Nested
    @DisplayName("fromBytes")
    class FromBytes {

        @Test
        @DisplayName("should return empty snapshot for null data")
        void shouldReturnEmptySnapshotForNullData() {
            HeapSnapshot snapshot = HeapSnapshot.fromBytes(null);

            assertThat(snapshot.entries()).isEmpty();
        }

        @Test
        @DisplayName("should return empty snapshot for empty data")
        void shouldReturnEmptySnapshotForEmptyData() {
            HeapSnapshot snapshot = HeapSnapshot.fromBytes(new byte[0]);

            assertThat(snapshot.entries()).isEmpty();
        }

        @Test
        @DisplayName("should return empty snapshot for insufficient header data")
        void shouldReturnEmptySnapshotForInsufficientHeaderData() {
            HeapSnapshot snapshot = HeapSnapshot.fromBytes(new byte[]{0x00, 0x01, 0x02});

            assertThat(snapshot.entries()).isEmpty();
        }

        @Test
        @DisplayName("should return empty snapshot for zero count")
        void shouldReturnEmptySnapshotForZeroCount() {
            ByteBuffer bb = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
            bb.putInt(0);

            HeapSnapshot snapshot = HeapSnapshot.fromBytes(bb.array());

            assertThat(snapshot.entries()).isEmpty();
        }

        @Test
        @DisplayName("should return empty snapshot for negative count")
        void shouldReturnEmptySnapshotForNegativeCount() {
            ByteBuffer bb = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
            bb.putInt(-1);

            HeapSnapshot snapshot = HeapSnapshot.fromBytes(bb.array());

            assertThat(snapshot.entries()).isEmpty();
        }

        @Test
        @DisplayName("should parse single entry")
        void shouldParseSingleEntry() {
            byte[] data = createSnapshotData(1, new EntryData(123L, "com.example.TestClass"));

            HeapSnapshot snapshot = HeapSnapshot.fromBytes(data);

            assertThat(snapshot.entries()).hasSize(1);
            assertThat(snapshot.entries().get(0).tag()).isEqualTo(123L);
            assertThat(snapshot.entries().get(0).className()).isEqualTo("com.example.TestClass");
        }

        @Test
        @DisplayName("should parse multiple entries")
        void shouldParseMultipleEntries() {
            byte[] data = createSnapshotData(3,
                    new EntryData(1L, "Class1"),
                    new EntryData(2L, "Class2"),
                    new EntryData(3L, "Class3")
            );

            HeapSnapshot snapshot = HeapSnapshot.fromBytes(data);

            assertThat(snapshot.entries()).hasSize(3);
            assertThat(snapshot.entries().get(0).tag()).isEqualTo(1L);
            assertThat(snapshot.entries().get(1).tag()).isEqualTo(2L);
            assertThat(snapshot.entries().get(2).tag()).isEqualTo(3L);
        }

        @Test
        @DisplayName("should handle UTF-8 class names")
        void shouldHandleUtf8ClassNames() {
            byte[] data = createSnapshotData(1, new EntryData(1L, "com.example.Über"));

            HeapSnapshot snapshot = HeapSnapshot.fromBytes(data);

            assertThat(snapshot.entries().get(0).className()).isEqualTo("com.example.Über");
        }

        @Test
        @DisplayName("should handle empty class name")
        void shouldHandleEmptyClassName() {
            byte[] data = createSnapshotData(1, new EntryData(1L, ""));

            HeapSnapshot snapshot = HeapSnapshot.fromBytes(data);

            assertThat(snapshot.entries().get(0).className()).isEmpty();
        }

        @Test
        @DisplayName("should stop at truncated entry - insufficient tag bytes")
        void shouldStopAtTruncatedEntryInsufficientTagBytes() {
            // Create header with count=2, but only provide enough data for 1 entry
            byte[] entry1 = createEntryBytes(1L, "Class1");
            ByteBuffer bb = ByteBuffer.allocate(4 + entry1.length + 4).order(ByteOrder.BIG_ENDIAN);
            bb.putInt(2); // count = 2
            bb.put(entry1);
            // Only 4 bytes remaining - not enough for tag (8) + length (4)

            HeapSnapshot snapshot = HeapSnapshot.fromBytes(bb.array());

            assertThat(snapshot.entries()).hasSize(1);
        }

        @Test
        @DisplayName("should stop at entry with negative class name length")
        void shouldStopAtEntryWithNegativeClassNameLength() {
            ByteBuffer bb = ByteBuffer.allocate(4 + 8 + 4).order(ByteOrder.BIG_ENDIAN);
            bb.putInt(1);      // count
            bb.putLong(1L);    // tag
            bb.putInt(-1);     // negative length

            HeapSnapshot snapshot = HeapSnapshot.fromBytes(bb.array());

            assertThat(snapshot.entries()).isEmpty();
        }

        @Test
        @DisplayName("should stop at entry with class name length exceeding remaining bytes")
        void shouldStopAtEntryWithExcessiveClassNameLength() {
            ByteBuffer bb = ByteBuffer.allocate(4 + 8 + 4 + 5).order(ByteOrder.BIG_ENDIAN);
            bb.putInt(1);      // count
            bb.putLong(1L);    // tag
            bb.putInt(100);    // length exceeds remaining bytes
            bb.put("short".getBytes(StandardCharsets.UTF_8)); // only 5 bytes

            HeapSnapshot snapshot = HeapSnapshot.fromBytes(bb.array());

            assertThat(snapshot.entries()).isEmpty();
        }
    }

    @Nested
    @DisplayName("heap size limits")
    class HeapSizeLimits {

        @Test
        @DisplayName("should cap initial allocation at 1024 entries")
        void shouldCapInitialAllocationAt1024() {
            // Create data claiming 100000 entries but only provide a few
            ByteBuffer bb = ByteBuffer.allocate(4 + 3 * (8 + 4 + 5)).order(ByteOrder.BIG_ENDIAN);
            bb.putInt(100000); // claim 100000 entries
            // Only provide 3 entries
            for (int i = 0; i < 3; i++) {
                bb.putLong(i);
                bb.putInt(5);
                bb.put("Class".getBytes(StandardCharsets.UTF_8));
            }

            HeapSnapshot snapshot = HeapSnapshot.fromBytes(bb.array());

            // Should only parse the 3 entries actually present
            assertThat(snapshot.entries()).hasSize(3);
        }

        @Test
        @DisplayName("should handle count larger than actual data without OOM")
        void shouldHandleCountLargerThanActualDataWithoutOOM() {
            // Malicious data: claims Integer.MAX_VALUE entries
            ByteBuffer bb = ByteBuffer.allocate(4 + 8 + 4 + 4).order(ByteOrder.BIG_ENDIAN);
            bb.putInt(Integer.MAX_VALUE); // huge count - should not allocate MAX_VALUE ArrayList
            bb.putLong(1L);
            bb.putInt(4);
            bb.put("Test".getBytes(StandardCharsets.UTF_8));

            // Should not throw OutOfMemoryError
            HeapSnapshot snapshot = HeapSnapshot.fromBytes(bb.array());

            assertThat(snapshot.entries()).hasSize(1);
        }

        @Test
        @DisplayName("should parse exactly count entries when all present")
        void shouldParseExactlyCountEntriesWhenAllPresent() {
            int count = 100;
            EntryData[] entries = new EntryData[count];
            for (int i = 0; i < count; i++) {
                entries[i] = new EntryData(i, "Class" + i);
            }
            byte[] data = createSnapshotData(count, entries);

            HeapSnapshot snapshot = HeapSnapshot.fromBytes(data);

            assertThat(snapshot.entries()).hasSize(count);
        }

        @Test
        @DisplayName("should handle very large tag values")
        void shouldHandleVeryLargeTagValues() {
            byte[] data = createSnapshotData(1, new EntryData(Long.MAX_VALUE, "MaxTag"));

            HeapSnapshot snapshot = HeapSnapshot.fromBytes(data);

            assertThat(snapshot.entries().get(0).tag()).isEqualTo(Long.MAX_VALUE);
        }

        @Test
        @DisplayName("should handle negative tag values (epoch-based tags)")
        void shouldHandleNegativeTagValues() {
            byte[] data = createSnapshotData(1, new EntryData(-1L, "NegativeTag"));

            HeapSnapshot snapshot = HeapSnapshot.fromBytes(data);

            assertThat(snapshot.entries().get(0).tag()).isEqualTo(-1L);
        }
    }

    @Nested
    @DisplayName("Entry")
    class EntryTests {

        @Test
        @DisplayName("should store tag and className")
        void shouldStoreTagAndClassName() {
            HeapSnapshot.Entry entry = new HeapSnapshot.Entry(42L, "com.example.Test");

            assertThat(entry.tag()).isEqualTo(42L);
            assertThat(entry.className()).isEqualTo("com.example.Test");
        }

        @Test
        @DisplayName("toString should include tag and class")
        void toStringShouldIncludeTagAndClass() {
            HeapSnapshot.Entry entry = new HeapSnapshot.Entry(123L, "MyClass");

            String str = entry.toString();

            assertThat(str).contains("123");
            assertThat(str).contains("MyClass");
        }
    }

    // Helper class for test data
    private record EntryData(long tag, String className) {}

    // Helper method to create snapshot binary data
    private byte[] createSnapshotData(int count, EntryData... entries) {
        int totalSize = 4; // count
        for (EntryData entry : entries) {
            totalSize += 8 + 4 + entry.className.getBytes(StandardCharsets.UTF_8).length;
        }

        ByteBuffer bb = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN);
        bb.putInt(count);

        for (EntryData entry : entries) {
            byte[] classNameBytes = entry.className.getBytes(StandardCharsets.UTF_8);
            bb.putLong(entry.tag);
            bb.putInt(classNameBytes.length);
            bb.put(classNameBytes);
        }

        return bb.array();
    }

    // Helper method to create single entry bytes
    private byte[] createEntryBytes(long tag, String className) {
        byte[] classNameBytes = className.getBytes(StandardCharsets.UTF_8);
        ByteBuffer bb = ByteBuffer.allocate(8 + 4 + classNameBytes.length).order(ByteOrder.BIG_ENDIAN);
        bb.putLong(tag);
        bb.putInt(classNameBytes.length);
        bb.put(classNameBytes);
        return bb.array();
    }
}
