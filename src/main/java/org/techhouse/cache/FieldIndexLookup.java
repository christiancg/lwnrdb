package org.techhouse.cache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.techhouse.cache.heap.HeapLayout;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.IdSet;

public final class FieldIndexLookup {
    private static final int GOLDEN_RATIO = 0x9E3779B9;
    private static final int MIN_TABLE_LENGTH = IdSet.tableLengthFor(0);

    private final List<String> typeIdentifiers = new ArrayList<>(4);
    private final Map<String, Located> extra = new HashMap<>();
    private final AtomicInteger size = new AtomicInteger();
    private Table table;

    public record Located(String typeIdentifier, FieldIndexEntry<Object> entry, Located next) {
    }

    private static final class Table {
        private final String[] ids;
        private final FieldIndexEntry<?>[] entries;
        private final byte[] types;
        private int occupied;

        private Table(int length) {
            ids = new String[length];
            entries = new FieldIndexEntry<?>[length];
            types = new byte[length];
        }

        private int probe(String id) {
            final var mask = ids.length - 1;
            var at = home(id);
            while (ids[at] != null && !ids[at].equals(id)) {
                at = (at + 1) & mask;
            }
            return at;
        }

        private int home(String id) {
            return (id.hashCode() * GOLDEN_RATIO) >>> Integer.numberOfLeadingZeros(ids.length - 1);
        }

        private void place(int at, String id, byte type, FieldIndexEntry<?> entry) {
            ids[at] = id;
            entries[at] = entry;
            types[at] = type;
        }

        private void clear(int at) {
            ids[at] = null;
            entries[at] = null;
            types[at] = 0;
        }

        private void insert(int at, String id, byte type, FieldIndexEntry<?> entry) {
            place(at, id, type, entry);
            occupied++;
        }

        private void remove(int emptied) {
            clear(emptied);
            occupied--;
            final var mask = ids.length - 1;
            var gap = emptied;
            var next = (gap + 1) & mask;
            while (ids[next] != null) {
                final var wanted = home(ids[next]);
                if (((next - wanted) & mask) >= ((next - gap) & mask)) {
                    place(gap, ids[next], types[next], entries[next]);
                    clear(next);
                    gap = next;
                }
                next = (next + 1) & mask;
            }
        }

        private Table resized(int length) {
            final var resized = new Table(length);
            for (var i = 0; i < ids.length; i++) {
                if (ids[i] != null) {
                    resized.insert(resized.probe(ids[i]), ids[i], types[i], entries[i]);
                }
            }
            return resized;
        }
    }

    public FieldIndexLookup() {
        this(0);
    }

    public FieldIndexLookup(int expectedIds) {
        table = new Table(IdSet.tableLengthFor(expectedIds));
    }

    public static long estimatedBytes(int ids, HeapLayout layout) {
        final var length = IdSet.tableLengthFor(ids);
        return layout.shallowSize(FieldIndexLookup.class) + layout.shallowSize(ArrayList.class)
                + layout.shallowSize(HashMap.class) + layout.shallowSize(AtomicInteger.class)
                + layout.shallowSize(Table.class) + 2 * layout.referenceArraySize(length)
                + layout.arraySize(Byte.BYTES, length);
    }

    public Located locate(String id) {
        if (!extra.isEmpty()) {
            final var several = extra.get(id);
            if (several != null) {
                return several;
            }
        }
        final var at = table.probe(id);
        return table.ids[at] == null ? null : locatedAt(at);
    }

    @SuppressWarnings("unchecked")
    private Located locatedAt(int at) {
        return new Located(typeIdentifiers.get(table.types[at]), (FieldIndexEntry<Object>) table.entries[at], null);
    }

    public void add(String id, String typeIdentifier, FieldIndexEntry<Object> entry) {
        final var several = extra.isEmpty() ? null : extra.get(id);
        if (several != null) {
            for (var at = several; at != null; at = at.next()) {
                if (at.entry() == entry) {
                    return;
                }
            }
            extra.put(id, new Located(typeIdentifier, entry, several));
            return;
        }
        final var at = table.probe(id);
        if (table.ids[at] == null) {
            insertAt(at, id, typeIdentifier, entry);
            publishSize();
        } else if (table.entries[at] != entry) {
            final var current = locatedAt(at);
            removeAt(at);
            extra.put(id, new Located(typeIdentifier, entry, current));
        }
    }

    public void moved(String id, Located now) {
        if (!extra.isEmpty()) {
            extra.remove(id);
        }
        final var at = table.probe(id);
        final var wasSingle = table.ids[at] != null;
        if (now == null) {
            if (wasSingle) {
                removeAt(at);
            }
        } else if (now.next() != null) {
            if (wasSingle) {
                removeAt(at);
            }
            extra.put(id, now);
        } else if (wasSingle) {
            table.entries[at] = now.entry();
            table.types[at] = typeOrdinal(now.typeIdentifier());
        } else {
            insertAt(at, id, now.typeIdentifier(), now.entry());
        }
        publishSize();
    }

    public int size() {
        return size.get();
    }

    private void publishSize() {
        size.set(table.occupied + extra.size());
    }

    private byte typeOrdinal(String typeIdentifier) {
        final var known = typeIdentifiers.indexOf(typeIdentifier);
        if (known >= 0) {
            return (byte) known;
        }
        typeIdentifiers.add(typeIdentifier);
        return (byte) (typeIdentifiers.size() - 1);
    }

    private void insertAt(int at, String id, String typeIdentifier, FieldIndexEntry<?> entry) {
        final var type = typeOrdinal(typeIdentifier);
        if ((long) (table.occupied + 1) * 4 > (long) table.ids.length * 3) {
            table = table.resized(table.ids.length << 1);
            table.insert(table.probe(id), id, type, entry);
        } else {
            table.insert(at, id, type, entry);
        }
    }

    private void removeAt(int at) {
        table.remove(at);
        final var fitting = IdSet.tableLengthFor(table.occupied);
        if (table.ids.length > MIN_TABLE_LENGTH && table.ids.length > 2 * fitting) {
            table = table.resized(fitting);
        }
    }
}
