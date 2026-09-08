package org.techhouse.simplejs.values;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.techhouse.simplejs.builtins.InterpreterOps;

public final class JsArray extends JsValue {
    static final JsValue HOLE = JsUndefined.getHole();
    public static final int MAX_DENSE_LENGTH = 1 << 24;
    static final ThreadLocal<InterpreterOps> LENGTH_COERCION_OPS = new ThreadLocal<>();

    final List<JsValue> elements = new ArrayList<>();
    final PropertyTable table = new PropertyTable();
    Map<Integer, JsValue> sparseValues;
    boolean frozen;
    boolean sealed;
    Map<Integer, JsObject.PropertyFlags> indexFlags;
    Map<Integer, JsValue> indexGetters;
    Map<Integer, JsValue> indexSetters;
    private Set<Integer> indexAccessorKeys;
    JsObject.PropertyFlags lengthFlags = new JsObject.PropertyFlags(true, false, false);
    private JsValue proto;
    long length;

    final JsArrayLength lengths = new JsArrayLength(this);
    final JsArrayProperties props = new JsArrayProperties(this);

    public JsArray() {
    }

    @Override
    public PropertyTable ownProperties() {
        return table;
    }

    @Override
    public JsValue getProto() {
        return proto;
    }

    @Override
    public void setProto(JsValue proto) {
        this.proto = proto;
    }

    public JsArray(List<JsValue> initial) {
        elements.addAll(initial);
        length = initial.size();
    }

    private boolean hasValueAt(int index) {
        if (index < elements.size()) {
            return elements.get(index) != HOLE;
        }
        return sparseValues != null && sparseValues.containsKey(index);
    }

    public JsValue get(int index) {
        if (index < 0) {
            return JsUndefined.getInstance();
        }
        if (index < elements.size()) {
            return elements.get(index);
        }
        final var sparse = sparseValues == null ? null : sparseValues.get(index);
        return sparse == null ? JsUndefined.getInstance() : sparse;
    }

    public boolean isHole(int index) {
        if (index < 0 || index >= length) {
            return true;
        }
        return !hasValueAt(index);
    }

    public void pushHole() {
        if (!frozen) {
            final var newLength = length + 1;
            if (newLength <= MAX_DENSE_LENGTH) {
                lengths.padTo((int) newLength);
            }
            length = newLength;
        }
    }

    public void clearIndexToHole(int index) {
        if (index >= 0 && index < elements.size()) {
            elements.set(index, HOLE);
        } else if (sparseValues != null) {
            sparseValues.remove(index);
        }
        clearIndexAccessor(index);
        if (indexFlags != null) {
            indexFlags.remove(index);
        }
    }

    public int removeHoles() {
        var removed = 0;
        final var iterator = elements.iterator();
        while (iterator.hasNext()) {
            if (iterator.next() == HOLE) {
                iterator.remove();
                removed++;
            }
        }
        length -= removed;
        return removed;
    }

    public boolean set(int index, JsValue value) {
        if (frozen || (!table.isExtensible() && index >= length)) {
            return false;
        }
        if (hasValueAt(index) && !getIndexFlags(index).writable()) {
            return false;
        }
        storeIndexValue(index, value);
        return true;
    }

    public void defineIndexValue(int index, JsValue value) {
        storeIndexValue(index, value);
    }

    private void storeIndexValue(int index, JsValue value) {
        if (index < MAX_DENSE_LENGTH) {
            lengths.padToIndex(index);
            elements.set(index, value);
        } else {
            if (sparseValues == null) {
                sparseValues = new HashMap<>();
            }
            sparseValues.put(index, value);
        }
        if (index + 1L > length) {
            length = index + 1L;
        }
    }

    public boolean setWideIndex(long index, JsValue value) {
        if (index <= Integer.MAX_VALUE) {
            return set((int) index, value);
        }
        if (frozen || (!table.isExtensible() && index >= length)) {
            return false;
        }
        final var name = Long.toString(index);
        if (table.has(name) && !table.getFlags(name).writable()) {
            return false;
        }
        table.set(name, value);
        if (index + 1 > length) {
            length = index + 1;
        }
        return true;
    }

    public void defineIndexAccessor(int index, JsValue getter, JsValue setter) {
        if (indexAccessorKeys == null) {
            indexAccessorKeys = new LinkedHashSet<>();
        }
        indexAccessorKeys.add(index);
        if (getter != null) {
            if (indexGetters == null) {
                indexGetters = new LinkedHashMap<>();
            }
            indexGetters.put(index, getter);
        }
        if (setter != null) {
            if (indexSetters == null) {
                indexSetters = new LinkedHashMap<>();
            }
            indexSetters.put(index, setter);
        }
    }

    public JsValue getIndexAccessorGetter(int index) {
        return indexGetters == null ? null : indexGetters.get(index);
    }

    public JsValue getIndexAccessorSetter(int index) {
        return indexSetters == null ? null : indexSetters.get(index);
    }

    public boolean hasAnyIndexAccessor() {
        return indexAccessorKeys != null && !indexAccessorKeys.isEmpty();
    }

    public boolean hasIndexAccessor(int index) {
        return indexAccessorKeys != null && indexAccessorKeys.contains(index);
    }

    public void clearIndexAccessor(int index) {
        if (indexGetters != null) {
            indexGetters.remove(index);
        }
        if (indexSetters != null) {
            indexSetters.remove(index);
        }
        if (indexAccessorKeys != null) {
            indexAccessorKeys.remove(index);
        }
    }

    public JsObject.PropertyFlags getIndexFlags(int index) {
        final var stored = indexFlags == null ? null : indexFlags.get(index);
        var flags = stored == null ? JsObject.PropertyFlags.DEFAULT : stored;
        if (frozen) {
            flags = new JsObject.PropertyFlags(false, flags.enumerable(), false);
        } else if (sealed) {
            flags = new JsObject.PropertyFlags(flags.writable(), flags.enumerable(), false);
        }
        return flags;
    }

    public void setIndexFlags(int index, JsObject.PropertyFlags flags) {
        if (indexFlags == null) {
            indexFlags = new LinkedHashMap<>();
        }
        indexFlags.put(index, flags);
    }

    public boolean push(JsValue value) {
        if (frozen || !table.isExtensible()) {
            return false;
        }
        storeIndexValue((int) Math.min(length, Integer.MAX_VALUE), value);
        return true;
    }

    public JsValue getProperty(String key) {
        return table.has(key) ? table.get(key) : null;
    }

    public boolean setProperty(String key, JsValue value) {
        return !frozen && table.set(key, value);
    }

    public boolean hasProperty(String key) {
        return table.has(key);
    }

    public JsObject.PropertyFlags getPropFlags(String key) {
        var flags = table.getFlags(key);
        if (frozen) {
            flags = new JsObject.PropertyFlags(false, flags.enumerable(), false);
        } else if (sealed) {
            flags = new JsObject.PropertyFlags(flags.writable(), flags.enumerable(), false);
        }
        return flags;
    }

    public boolean deleteProperty(String key) {
        table.delete(key);
        clearPropAccessor(key);
        return true;
    }

    public JsValue getPropAccessorGetter(String key) {
        return table.getAccessorGetter(key);
    }

    public JsValue getPropAccessorSetter(String key) {
        return table.getAccessorSetter(key);
    }

    public boolean hasPropAccessor(String key) {
        return table.hasAccessor(key);
    }

    public void clearPropAccessor(String key) {
        table.clearAccessor(key);
    }

    public void freeze() {
        frozen = true;
        sealed = true;
        table.freeze();
        lengthFlags = new JsObject.PropertyFlags(false, lengthFlags.enumerable(), false);
    }

    public boolean isFrozen() {
        return frozen || (!table.isExtensible() && elements.isEmpty()
                && (sparseValues == null || sparseValues.isEmpty()) && table.isFrozen());
    }

    public void seal() {
        sealed = true;
        table.seal();
    }

    public boolean isSealed() {
        return sealed || isFrozen();
    }

    public void preventExtensions() {
        table.preventExtensions();
    }

    @Override
    public boolean isExtensible() {
        return table.isExtensible();
    }

    public List<JsValue> getElements() {
        return new ElementsView();
    }

    private final class ElementsView extends java.util.AbstractList<JsValue> {
        @Override
        public JsValue get(int index) {
            return elements.get(index);
        }

        @Override
        public int size() {
            return elements.size();
        }

        @Override
        public JsValue set(int index, JsValue element) {
            final var previous = elements.set(index, element);
            if (index + 1L > length) {
                length = index + 1L;
            }
            return previous;
        }

        @Override
        public void add(int index, JsValue element) {
            elements.add(index, element);
            if (elements.size() > length) {
                length = elements.size();
            }
        }

        @Override
        public JsValue remove(int index) {
            final var removed = elements.remove(index);
            if (length > elements.size()) {
                length = elements.size();
            }
            return removed;
        }
    }
    public static void withLengthCoercionOps(InterpreterOps ops, Runnable action) {
        JsArrayLength.withLengthCoercionOps(ops, action);
    }

    public long length() {
        return lengths.length();
    }

    public boolean setLength(long newLength) {
        return lengths.setLength(newLength);
    }

    public java.util.Set<String> namedPropertyKeys() {
        return props.namedPropertyKeys();
    }

    public boolean ownsIndex(int index) {
        return props.ownsIndex(index);
    }

    @Override
    public List<JsValue> ownPropertyKeys() {
        return props.ownPropertyKeys();
    }

    @Override
    public PropertyDescriptor getOwnProperty(JsValue key) {
        return props.getOwnProperty(key);
    }

    @Override
    public boolean defineOwnProperty(JsValue key, PropertyDescriptor descriptor) {
        return props.defineOwnProperty(key, descriptor);
    }

    @Override
    public boolean deleteOwnProperty(JsValue key) {
        return props.deleteOwnProperty(key);
    }
    PropertyDescriptor superGetOwnProperty(JsValue key) {
        return super.getOwnProperty(key);
    }

    boolean superDefineOwnProperty(JsValue key, PropertyDescriptor descriptor) {
        return super.defineOwnProperty(key, descriptor);
    }

    boolean superDeleteOwnProperty(JsValue key) {
        return super.deleteOwnProperty(key);
    }

    public void setLengthWritable(boolean writable) {
        lengths.setLengthWritable(writable);
    }
}
