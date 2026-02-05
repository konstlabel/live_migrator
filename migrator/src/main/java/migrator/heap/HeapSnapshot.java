package migrator.heap;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Immutable snapshot of heap objects of a specific class type.
 *
 * <p>A HeapSnapshot contains a list of {@link Entry} objects, each representing
 * a live object on the heap. Each entry has a unique tag (used for resolution)
 * and the class name of the object.
 *
 * <p>Snapshots are typically created by {@link HeapWalker#snapshot(Class)} and
 * can be deserialized from a binary format using {@link #fromBytes(byte[])}.
 *
 * @see HeapWalker
 * @see Entry
 */
public final class HeapSnapshot {

    /**
     * Represents a single object entry in a heap snapshot.
     *
     * <p>Each entry contains:
     * <ul>
     *   <li>A unique tag for resolving the actual object reference</li>
     *   <li>The fully qualified class name of the object</li>
     * </ul>
     */
    public static final class Entry {
        private final long tag;
        private final String className;

        /**
         * Creates a new heap entry.
         *
         * @param tag the unique tag for resolving this object
         * @param className the fully qualified class name
         */
        public Entry(long tag, String className) {
            this.tag = tag;
            this.className = className;
        }

        /**
         * Returns the unique tag for resolving this object reference.
         *
         * @return the object tag
         */
        public long tag() {
            return tag;
        }

        /**
         * Returns the fully qualified class name of the object.
         *
         * @return the class name
         */
        public String className() {
            return className;
        }

        @Override
        public String toString() {
            return "Entry[tag=" + tag + ", class=" + className + "]";
        }
    }


    private final List<Entry> entries;

    /**
     * Creates a new heap snapshot with the given entries.
     *
     * @param entries the list of heap entries (will be copied)
     */
    public HeapSnapshot(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    /**
     * Returns the list of entries in this snapshot.
     *
     * @return an immutable list of heap entries
     */
    public List<Entry> entries() {
        return entries;
    }

    /**
     * Deserializes a heap snapshot from binary data.
     *
     * <p>The binary format consists of:
     * <ul>
     *   <li>4 bytes: entry count (big-endian int)</li>
     *   <li>For each entry:
     *     <ul>
     *       <li>8 bytes: object tag (big-endian long)</li>
     *       <li>4 bytes: class name length (big-endian int)</li>
     *       <li>N bytes: UTF-8 encoded class name</li>
     *     </ul>
     *   </li>
     * </ul>
     *
     * @param data the binary data to deserialize
     * @return a new HeapSnapshot, or an empty snapshot if data is null/invalid
     */
    public static HeapSnapshot fromBytes(byte[] data) {
        if (data == null || data.length < Integer.BYTES) {
            return new HeapSnapshot(List.of());
        }

        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

        if (bb.remaining() < Integer.BYTES) {
            return new HeapSnapshot(List.of());
        }

        int count = bb.getInt();
        if (count <= 0) {
            return new HeapSnapshot(List.of());
        }

        List<Entry> out = new ArrayList<>(Math.min(count, 1024));

        int i = 0;
        while (i < count && canReadEntry(bb)) {
            Entry entry = readEntry(bb);
            if (entry == null) {
                break;
            }
            out.add(entry);
            i++;
        }

        return new HeapSnapshot(out);
    }

    private static boolean canReadEntry(ByteBuffer bb) {
        return bb.remaining() >= Long.BYTES + Integer.BYTES;
    }

    private static Entry readEntry(ByteBuffer bb) {
        long tag = bb.getLong();
        int len = bb.getInt();

        if (len < 0 || len > bb.remaining()) {
            return null;
        }

        byte[] b = new byte[len];
        bb.get(b);

        return new Entry(tag, new String(b, StandardCharsets.UTF_8));
    }
}
