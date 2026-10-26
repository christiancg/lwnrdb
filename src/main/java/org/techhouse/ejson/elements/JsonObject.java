package org.techhouse.ejson.elements;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

public class JsonObject extends JsonBaseElement {
    private static final String[] NO_KEYS = {};
    private static final JsonBaseElement[] NO_VALUES = {};
    private static final int INDEXED_ABOVE = 16;
    private static final int FIRST_CAPACITY = 4;
    private static final long SLOT_SEED = seedFrom(new java.security.SecureRandom());
    private static final long SLOT_MULTIPLIER = 0x9E3779B97F4A7C15L;

    private String[] keys;
    private JsonBaseElement[] values;
    private int size;
    private Object memberIndex;

    public JsonObject() {
        keys = NO_KEYS;
        values = NO_VALUES;
    }

    public JsonObject(int expectedMembers) {
        keys = expectedMembers == 0 ? NO_KEYS : new String[expectedMembers];
        values = expectedMembers == 0 ? NO_VALUES : new JsonBaseElement[expectedMembers];
    }

    public static JsonObject withShape(KeyShape shape, JsonBaseElement[] values) {
        if (values.length != shape.size()) {
            throw new IllegalArgumentException("A shape of " + shape.size() + " keys needs as many values");
        }
        final var object = new JsonObject();
        object.keys = shape.keys();
        object.values = values;
        object.size = values.length;
        object.memberIndex = shape;
        return object;
    }

    public static KeyShape shapeOf(String[] source, int from, int to) {
        final var keys = Arrays.copyOfRange(source, from, to);
        for (final var key : keys) {
            Objects.requireNonNull(key, "property == null");
        }
        final var slots = keys.length > INDEXED_ABOVE ? slotsFor(keys, keys.length) : null;
        if (slots == null ? hasDuplicateKey(keys) : slots.length == 0) {
            return null;
        }
        return new KeyShape(keys, slots, KeyShape.hashOf(source, from, to));
    }

    private static boolean hasDuplicateKey(String[] keys) {
        for (var i = 1; i < keys.length; i++) {
            for (var j = 0; j < i; j++) {
                if (keys[i].equals(keys[j])) {
                    return true;
                }
            }
        }
        return false;
    }

    public KeyShape shape() {
        return memberIndex instanceof KeyShape shared ? shared : null;
    }

    private int[] slots() {
        return memberIndex instanceof KeyShape shared ? shared.slots() : (int[]) memberIndex;
    }

    private void ownKeys() {
        if (!(memberIndex instanceof KeyShape shared)) {
            return;
        }
        keys = keys.clone();
        memberIndex = shared.slots() == null ? null : shared.slots().clone();
    }

    public void add(String property, JsonBaseElement value) {
        put(property, value == null ? JsonNull.INSTANCE : value);
    }

    public void add(String property, String value) {
        put(property, value == null ? JsonNull.INSTANCE : new JsonString(value));
    }

    private void put(String property, JsonBaseElement value) {
        Objects.requireNonNull(property, "property == null");
        final var at = indexOf(property);
        if (at >= 0) {
            values[at] = value;
            return;
        }
        ownKeys();
        if (size == keys.length) {
            grow();
        }
        keys[size] = property;
        values[size] = value;
        size++;
        if (memberIndex != null) {
            insertSlot(size - 1);
        } else if (size > INDEXED_ABOVE) {
            rebuildSlots();
        }
    }

    private void grow() {
        final var capacity = keys.length < FIRST_CAPACITY ? FIRST_CAPACITY : keys.length + (keys.length >> 1);
        keys = Arrays.copyOf(keys, capacity);
        values = Arrays.copyOf(values, capacity);
    }

    public void addProperty(String property, String value) {
        add(property, value);
    }

    public void addProperty(String property, Boolean value) {
        put(property, value == null ? JsonNull.INSTANCE : JsonBoolean.of(value));
    }

    public void addProperty(String property, Integer value) {
        put(property, value == null ? JsonNull.INSTANCE : new JsonNumber(value));
    }

    public void addProperty(String property, Number value) {
        put(property, value == null ? JsonNull.INSTANCE : new JsonNumber(value));
    }

    public void addProperty(String property, Long value) {
        put(property, value == null ? JsonNull.INSTANCE : new JsonNumber(value));
    }

    public void remove(String property) {
        final var at = indexOf(property);
        if (at < 0) {
            return;
        }
        ownKeys();
        final var after = size - at - 1;
        System.arraycopy(keys, at + 1, keys, at, after);
        System.arraycopy(values, at + 1, values, at, after);
        size--;
        keys[size] = null;
        values[size] = null;
        if (memberIndex != null) {
            rebuildSlots();
        }
    }

    public Set<Map.Entry<String, JsonBaseElement>> entrySet() {
        return new Members();
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public String keyAt(int index) {
        return keys[Objects.checkIndex(index, size)];
    }

    public JsonBaseElement valueAt(int index) {
        return values[Objects.checkIndex(index, size)];
    }

    public int capacity() {
        return keys.length;
    }

    public int indexSlots() {
        final var slots = slots();
        return slots == null ? 0 : slots.length;
    }

    public boolean has(String memberName) {
        return indexOf(memberName) >= 0;
    }

    public JsonBaseElement get(String memberName) {
        final var at = indexOf(memberName);
        return at < 0 ? null : values[at];
    }

    public int indexOf(String memberName) {
        if (memberName == null) {
            return -1;
        }
        final var slots = slots();
        if (slots != null) {
            return indexedPositionOf(slots, memberName);
        }
        for (var i = 0; i < size; i++) {
            if (memberName.equals(keys[i])) {
                return i;
            }
        }
        return -1;
    }

    private int indexedPositionOf(int[] slots, String memberName) {
        final var mask = slots.length - 1;
        for (var slot = homeSlot(memberName, mask);; slot = (slot + 1) & mask) {
            final var stored = slots[slot];
            if (stored == 0) {
                return -1;
            }
            if (memberName.equals(keys[stored - 1])) {
                return stored - 1;
            }
        }
    }

    private static int homeSlot(String key, int mask) {
        return slotHash(key) & mask;
    }

    private static long seedFrom(java.security.SecureRandom random) {
        return random.nextLong();
    }

    private static int slotHash(String key) {
        var hash = SLOT_SEED ^ key.length();
        for (var i = 0; i < key.length(); i++) {
            hash = slotMix(hash ^ key.charAt(i));
        }
        return Long.hashCode(hash);
    }

    private static long slotMix(long value) {
        var mixed = value * SLOT_MULTIPLIER;
        mixed ^= mixed >>> 32;
        mixed *= SLOT_MULTIPLIER;
        return mixed ^ (mixed >>> 29);
    }

    private void rebuildSlots() {
        memberIndex = size <= INDEXED_ABOVE ? null : slotsFor(keys, size);
    }

    private static int[] slotsFor(String[] keys, int count) {
        final var table = new int[Integer.highestOneBit(count * 4 - 1)];
        final var mask = table.length - 1;
        for (var position = 0; position < count; position++) {
            var slot = homeSlot(keys[position], mask);
            while (table[slot] != 0) {
                if (keys[table[slot] - 1].equals(keys[position])) {
                    return new int[0];
                }
                slot = (slot + 1) & mask;
            }
            table[slot] = position + 1;
        }
        return table;
    }

    private void insertSlot(int position) {
        final var slots = (int[]) memberIndex;
        if ((size << 1) > slots.length) {
            rebuildSlots();
            return;
        }
        final var mask = slots.length - 1;
        var slot = homeSlot(keys[position], mask);
        while (slots[slot] != 0) {
            slot = (slot + 1) & mask;
        }
        slots[slot] = position + 1;
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof JsonObject other) || other.size != size) {
            return false;
        }
        for (var i = 0; i < size; i++) {
            final var at = other.indexOf(keys[i]);
            if (at < 0 || !values[i].equals(other.values[at])) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        var hash = 0;
        for (var i = 0; i < size; i++) {
            hash += keys[i].hashCode() ^ values[i].hashCode();
        }
        return hash;
    }

    @Override
    public JsonObject deepCopy() {
        if (memberIndex instanceof KeyShape shared) {
            final var copied = new JsonBaseElement[size];
            for (var i = 0; i < size; i++) {
                copied[i] = values[i].deepCopy();
            }
            return withShape(shared, copied);
        }
        final var result = new JsonObject(size);
        for (var i = 0; i < size; i++) {
            result.put(keys[i], values[i].deepCopy());
        }
        return result;
    }

    public JsonObject copyForWrites(Set<String> writtenRoots) {
        if (memberIndex instanceof KeyShape shared) {
            final var copied = new JsonBaseElement[size];
            for (var i = 0; i < size; i++) {
                copied[i] = writtenRoots.contains(keys[i]) ? values[i].deepCopy() : values[i];
            }
            return withShape(shared, copied);
        }
        final var result = new JsonObject(size);
        for (var i = 0; i < size; i++) {
            final var value = values[i];
            result.put(keys[i], writtenRoots.contains(keys[i]) ? value.deepCopy() : value);
        }
        return result;
    }

    public JsonObject copyWithout(String key) {
        final var result = new JsonObject(size);
        for (var i = 0; i < size; i++) {
            if (!keys[i].equals(key)) {
                result.put(keys[i], values[i]);
            }
        }
        return result;
    }

    private final class Members extends AbstractSet<Map.Entry<String, JsonBaseElement>> {
        @Override
        @SuppressWarnings("NullableProblems")
        public Iterator<Map.Entry<String, JsonBaseElement>> iterator() {
            return new Iterator<>() {
                private int next;

                @Override
                public boolean hasNext() {
                    return next < size;
                }

                @Override
                public Map.Entry<String, JsonBaseElement> next() {
                    if (next >= size) {
                        throw new NoSuchElementException();
                    }
                    final var at = next++;
                    return new AbstractMap.SimpleImmutableEntry<>(keys[at], values[at]);
                }
            };
        }

        @Override
        public int size() {
            return size;
        }
    }
}
