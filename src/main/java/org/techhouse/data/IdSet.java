package org.techhouse.data;

import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Predicate;

public final class IdSet extends AbstractSet<String> {
    public static final int PACKED_LIMIT = 8;
    private static final int MIN_TABLE_LENGTH = 16;
    private static final int GOLDEN_RATIO = 0x9E3779B9;
    private static final String[] NONE = new String[0];

    private String[] slots;
    private int size;
    private int modCount;

    public IdSet() {
        slots = NONE;
    }

    public IdSet(int expected) {
        slots = expected > PACKED_LIMIT ? new String[tableLengthFor(expected)] : NONE;
    }

    public static int slotsFor(int ids) {
        return ids <= PACKED_LIMIT ? ids : tableLengthFor(ids);
    }

    private static int tableLengthFor(int ids) {
        var length = MIN_TABLE_LENGTH;
        while ((long) ids * 4 > (long) length * 3) {
            length <<= 1;
        }
        return length;
    }

    private boolean packed() {
        return slots.length <= PACKED_LIMIT;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public boolean contains(Object candidate) {
        if (!(candidate instanceof String id)) {
            return false;
        }
        return packed() ? packedIndexOf(id) >= 0 : slots[probe(slots, id)] != null;
    }

    private int packedIndexOf(String id) {
        for (var i = 0; i < size; i++) {
            if (slots[i].equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private static int home(String[] table, String id) {
        return (id.hashCode() * GOLDEN_RATIO) >>> Integer.numberOfLeadingZeros(table.length - 1);
    }

    private static int probe(String[] table, String id) {
        final var mask = table.length - 1;
        var at = home(table, id);
        while (table[at] != null && !table[at].equals(id)) {
            at = (at + 1) & mask;
        }
        return at;
    }

    @Override
    public boolean add(String id) {
        Objects.requireNonNull(id);
        if (packed()) {
            if (packedIndexOf(id) >= 0) {
                return false;
            }
            if (size < PACKED_LIMIT) {
                final var grown = new String[size + 1];
                System.arraycopy(slots, 0, grown, 0, size);
                grown[size] = id;
                slots = grown;
                size++;
                modCount++;
                return true;
            }
            rehash(tableLengthFor(size + 1));
        }
        final var at = probe(slots, id);
        if (slots[at] != null) {
            return false;
        }
        if ((long) (size + 1) * 4 > (long) slots.length * 3) {
            rehash(slots.length << 1);
            slots[probe(slots, id)] = id;
        } else {
            slots[at] = id;
        }
        size++;
        modCount++;
        return true;
    }

    @Override
    public boolean remove(Object candidate) {
        if (!(candidate instanceof String id)) {
            return false;
        }
        if (packed()) {
            return removePacked(id);
        }
        final var at = probe(slots, id);
        if (slots[at] == null) {
            return false;
        }
        slots[at] = null;
        size--;
        modCount++;
        closeGap(at);
        if (size <= PACKED_LIMIT) {
            pack();
        } else if (slots.length > 2 * tableLengthFor(size)) {
            rehash(tableLengthFor(size));
        }
        return true;
    }

    private boolean removePacked(String id) {
        final var at = packedIndexOf(id);
        if (at < 0) {
            return false;
        }
        final var shrunk = size == 1 ? NONE : new String[size - 1];
        System.arraycopy(slots, 0, shrunk, 0, at);
        System.arraycopy(slots, at + 1, shrunk, at, size - at - 1);
        slots = shrunk;
        size--;
        modCount++;
        return true;
    }

    private void closeGap(int emptied) {
        final var mask = slots.length - 1;
        var gap = emptied;
        var next = (gap + 1) & mask;
        while (slots[next] != null) {
            final var wanted = home(slots, slots[next]);
            if (((next - wanted) & mask) >= ((next - gap) & mask)) {
                slots[gap] = slots[next];
                slots[next] = null;
                gap = next;
            }
            next = (next + 1) & mask;
        }
    }

    private void pack() {
        final var packedIds = size == 0 ? NONE : new String[size];
        var count = 0;
        for (final var id : slots) {
            if (id != null) {
                packedIds[count++] = id;
            }
        }
        slots = packedIds;
    }

    private void rehash(int length) {
        final var table = new String[length];
        for (final var id : slots) {
            if (id != null) {
                table[probe(table, id)] = id;
            }
        }
        slots = table;
    }

    @Override
    public void clear() {
        if (size > 0 || !packed()) {
            slots = NONE;
            size = 0;
            modCount++;
        }
    }

    @Override
    public boolean removeAll(Collection<?> ids) {
        if (ids == this) {
            final var changed = size > 0;
            clear();
            return changed;
        }
        var changed = false;
        for (final var id : ids) {
            changed |= remove(id);
        }
        return changed;
    }

    @Override
    @SuppressWarnings("NullableProblems")
    public boolean retainAll(Collection<?> ids) {
        return removeIf(id -> !ids.contains(id));
    }

    @Override
    @SuppressWarnings("NullableProblems")
    public boolean removeIf(Predicate<? super String> filter) {
        final var doomed = new ArrayList<String>();
        for (final var id : this) {
            if (filter.test(id)) {
                doomed.add(id);
            }
        }
        for (final var id : doomed) {
            remove(id);
        }
        return !doomed.isEmpty();
    }

    @Override
    @SuppressWarnings("NullableProblems")
    public Iterator<String> iterator() {
        return new Iterator<>() {
            private final String[] table = slots;
            private final int expected = modCount;
            private int cursor = advance(0);

            private int advance(int from) {
                var at = from;
                while (at < table.length && table[at] == null) {
                    at++;
                }
                return at;
            }

            @Override
            public boolean hasNext() {
                return cursor < table.length;
            }

            @Override
            public String next() {
                if (modCount != expected) {
                    throw new ConcurrentModificationException();
                }
                if (cursor >= table.length) {
                    throw new NoSuchElementException();
                }
                final var id = table[cursor];
                cursor = advance(cursor + 1);
                return id;
            }
        };
    }
}
