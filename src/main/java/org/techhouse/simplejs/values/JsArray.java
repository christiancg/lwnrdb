package org.techhouse.simplejs.values;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.techhouse.simplejs.exceptions.RangeErrorException;

public final class JsArray extends JsValue {
    private static final JsValue HOLE = JsUndefined.getHole();
    // Elements are stored densely, one slot per index, so a spec-legal length near 2^32 would have to
    // be materialised hole by hole - hours of padding and gigabytes of list. Until the representation
    // grows a sparse mode, a length that far out is refused rather than attempted.
    private static final int MAX_DENSE_LENGTH = 1 << 24;

    private final List<JsValue> elements = new ArrayList<>();
    private final PropertyTable table = new PropertyTable();
    private boolean frozen;
    private boolean sealed;
    // Per-property descriptor flags, consulted by Object.defineProperty/getOwnPropertyDescriptor -
    // sparse (absent key == JsObject.PropertyFlags.DEFAULT) since most array elements/props never
    // have their flags individually redefined.
    private Map<Integer, JsObject.PropertyFlags> indexFlags;
    // Accessor storage, separate from the plain element value slots.
    private Map<Integer, JsValue> indexGetters;
    private Map<Integer, JsValue> indexSetters;
    // Spec: Array "length" is always non-enumerable, non-configurable; only writable is mutable.
    private JsObject.PropertyFlags lengthFlags = new JsObject.PropertyFlags(true, false, false);

    public JsArray() {
    }

    @Override
    public PropertyTable ownProperties() {
        return table;
    }

    public JsArray(List<JsValue> initial) {
        elements.addAll(initial);
    }

    public JsValue get(int index) {
        if (index < 0 || index >= elements.size()) {
            return JsUndefined.getInstance();
        }
        return elements.get(index);
    }

    public boolean isHole(int index) {
        return index >= 0 && index < elements.size() && elements.get(index) == HOLE;
    }

    public void pushHole() {
        if (!frozen) {
            padTo(elements.size() + 1);
        }
    }

    // Backs `delete arr[i]` on a configurable index: the slot becomes a genuine hole (no longer an
    // own property) rather than merely holding undefined, and any accessor/flag override is dropped
    // so a later plain assignment to the same index starts from the ordinary defaults again.
    public void clearIndexToHole(int index) {
        if (index >= 0 && index < elements.size()) {
            elements.set(index, HOLE);
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
        return removed;
    }

    public boolean set(int index, JsValue value) {
        if (frozen || (!table.isExtensible() && index >= elements.size())) {
            return false;
        }
        if (index < elements.size() && !getIndexFlags(index).writable()) {
            return false;
        }
        padToIndex(index);
        elements.set(index, value);
        return true;
    }

    // [[DefineOwnProperty]] bypass: unlike set(), this ignores frozen/writable/extensible so
    // Object.defineProperty can place a value regardless of the slot's current flags (the caller is
    // responsible for the extensibility/configurability checks the spec performs beforehand).
    public void defineIndexValue(int index, JsValue value) {
        padToIndex(index);
        elements.set(index, value);
    }

    public void defineIndexAccessor(int index, JsValue getter, JsValue setter) {
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
        return (indexGetters != null && !indexGetters.isEmpty()) || (indexSetters != null && !indexSetters.isEmpty());
    }

    public boolean hasIndexAccessor(int index) {
        return (indexGetters != null && indexGetters.containsKey(index))
                || (indexSetters != null && indexSetters.containsKey(index));
    }

    public void clearIndexAccessor(int index) {
        if (indexGetters != null) {
            indexGetters.remove(index);
        }
        if (indexSetters != null) {
            indexSetters.remove(index);
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
        checkDenseBound(elements.size() + 1);
        elements.add(value);
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
        return frozen || (!table.isExtensible() && elements.isEmpty() && table.isFrozen());
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

    public int length() {
        return elements.size();
    }

    public boolean setLength(int length) {
        if (frozen || (sealed && length != elements.size())) {
            return false;
        }
        if (!table.isExtensible() && length > elements.size()) {
            return false;
        }
        if (!lengthFlags.writable() && length != elements.size()) {
            return false;
        }
        while (elements.size() > length) {
            elements.removeLast();
        }
        padTo(length);
        return true;
    }

    public JsObject.PropertyFlags getLengthFlags() {
        return lengthFlags;
    }

    public void setLengthWritable(boolean writable) {
        lengthFlags = new JsObject.PropertyFlags(writable, lengthFlags.enumerable(), false);
    }

    // [[DefineOwnProperty]] on "length" bypasses the writable check for the value itself (only a
    // later [[Set]] respects it), matching ArraySetLength's "set newLenDesc's [[Value]] first, then
    // apply the writable attribute" order.
    public void defineLength(int length) {
        while (elements.size() > length) {
            elements.removeLast();
        }
        padTo(length);
    }

    private void padToIndex(int index) {
        checkDenseBound(index);
        padTo(index + 1);
    }

    private void padTo(int length) {
        checkDenseBound(length);
        while (elements.size() < length) {
            elements.add(HOLE);
        }
    }

    private static void checkDenseBound(int length) {
        if (length > MAX_DENSE_LENGTH) {
            throw new RangeErrorException("Invalid array length");
        }
    }

    // Named (non-index) own property keys, merging plain data properties with accessor-only ones.
    public java.util.Set<String> namedPropertyKeys() {
        return table.keys();
    }

    public List<JsValue> getElements() {
        return elements;
    }
}
