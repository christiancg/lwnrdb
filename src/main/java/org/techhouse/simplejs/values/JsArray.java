package org.techhouse.simplejs.values;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JsArray extends JsValue {
    private static final JsValue HOLE = JsUndefined.getHole();

    private final List<JsValue> elements = new ArrayList<>();
    private Map<String, JsValue> ownProperties;
    private boolean frozen;
    private boolean sealed;
    private boolean extensible = true;
    // Per-property descriptor flags, consulted by Object.defineProperty/getOwnPropertyDescriptor -
    // sparse (absent key == JsObject.PropertyFlags.DEFAULT) since most array elements/props never
    // have their flags individually redefined.
    private Map<Integer, JsObject.PropertyFlags> indexFlags;
    private Map<String, JsObject.PropertyFlags> propFlags;
    // Accessor storage, separate from the plain elements/ownProperties value slots.
    private Map<String, JsValue> propGetters;
    private Map<String, JsValue> propSetters;
    private Map<Integer, JsValue> indexGetters;
    private Map<Integer, JsValue> indexSetters;
    // Spec: Array "length" is always non-enumerable, non-configurable; only writable is mutable.
    private JsObject.PropertyFlags lengthFlags = new JsObject.PropertyFlags(true, false, false);

    public JsArray() {
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
            elements.add(HOLE);
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
        if (frozen || (!extensible && index >= elements.size())) {
            return false;
        }
        if (index < elements.size() && !getIndexFlags(index).writable()) {
            return false;
        }
        while (elements.size() <= index) {
            elements.add(HOLE);
        }
        elements.set(index, value);
        return true;
    }

    // [[DefineOwnProperty]] bypass: unlike set(), this ignores frozen/writable/extensible so
    // Object.defineProperty can place a value regardless of the slot's current flags (the caller is
    // responsible for the extensibility/configurability checks the spec performs beforehand).
    public void defineIndexValue(int index, JsValue value) {
        while (elements.size() <= index) {
            elements.add(HOLE);
        }
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
        if (frozen || !extensible) {
            return false;
        }
        elements.add(value);
        return true;
    }

    public JsValue getProperty(String key) {
        return ownProperties == null ? null : ownProperties.get(key);
    }

    public boolean setProperty(String key, JsValue value) {
        if (frozen || (!extensible && !hasProperty(key))) {
            return false;
        }
        if (hasProperty(key) && !getPropFlags(key).writable()) {
            return false;
        }
        if (ownProperties == null) {
            ownProperties = new LinkedHashMap<>();
        }
        ownProperties.put(key, value);
        return true;
    }

    public boolean hasProperty(String key) {
        return ownProperties != null && ownProperties.containsKey(key);
    }

    // [[DefineOwnProperty]] bypass, mirroring defineIndexValue for named (non-index) properties.
    public void defineOwnProperty(String key, JsValue value) {
        if (ownProperties == null) {
            ownProperties = new LinkedHashMap<>();
        }
        ownProperties.put(key, value);
    }

    public JsObject.PropertyFlags getPropFlags(String key) {
        final var stored = propFlags == null ? null : propFlags.get(key);
        var flags = stored == null ? JsObject.PropertyFlags.DEFAULT : stored;
        if (frozen) {
            flags = new JsObject.PropertyFlags(false, flags.enumerable(), false);
        } else if (sealed) {
            flags = new JsObject.PropertyFlags(flags.writable(), flags.enumerable(), false);
        }
        return flags;
    }

    public void setPropFlags(String key, JsObject.PropertyFlags flags) {
        if (propFlags == null) {
            propFlags = new LinkedHashMap<>();
        }
        propFlags.put(key, flags);
    }

    public boolean deleteProperty(String key) {
        if (ownProperties != null) {
            ownProperties.remove(key);
        }
        if (propFlags != null) {
            propFlags.remove(key);
        }
        clearPropAccessor(key);
        return true;
    }

    public void definePropAccessor(String key, JsValue getter, JsValue setter) {
        if (getter != null) {
            if (propGetters == null) {
                propGetters = new LinkedHashMap<>();
            }
            propGetters.put(key, getter);
        }
        if (setter != null) {
            if (propSetters == null) {
                propSetters = new LinkedHashMap<>();
            }
            propSetters.put(key, setter);
        }
    }

    public JsValue getPropAccessorGetter(String key) {
        return propGetters == null ? null : propGetters.get(key);
    }

    public JsValue getPropAccessorSetter(String key) {
        return propSetters == null ? null : propSetters.get(key);
    }

    public boolean hasPropAccessor(String key) {
        return (propGetters != null && propGetters.containsKey(key))
                || (propSetters != null && propSetters.containsKey(key));
    }

    public void clearPropAccessor(String key) {
        if (propGetters != null) {
            propGetters.remove(key);
        }
        if (propSetters != null) {
            propSetters.remove(key);
        }
    }

    public void freeze() {
        frozen = true;
        sealed = true;
        extensible = false;
        lengthFlags = new JsObject.PropertyFlags(false, lengthFlags.enumerable(), false);
    }

    public boolean isFrozen() {
        return frozen || (!extensible && elements.isEmpty());
    }

    public void seal() {
        sealed = true;
        extensible = false;
    }

    public boolean isSealed() {
        return sealed || isFrozen();
    }

    public void preventExtensions() {
        extensible = false;
    }

    public boolean isExtensible() {
        return extensible;
    }

    public int length() {
        return elements.size();
    }

    public boolean setLength(int length) {
        if (frozen || (sealed && length != elements.size())) {
            return false;
        }
        if (!extensible && length > elements.size()) {
            return false;
        }
        if (!lengthFlags.writable() && length != elements.size()) {
            return false;
        }
        while (elements.size() > length) {
            elements.removeLast();
        }
        while (elements.size() < length) {
            elements.add(HOLE);
        }
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
        while (elements.size() < length) {
            elements.add(HOLE);
        }
    }

    // Named (non-index) own property keys, merging plain data properties with accessor-only ones
    // (an accessor property has no entry in ownProperties).
    public java.util.Set<String> namedPropertyKeys() {
        final var keys = new java.util.LinkedHashSet<String>();
        if (ownProperties != null) {
            keys.addAll(ownProperties.keySet());
        }
        if (propGetters != null) {
            keys.addAll(propGetters.keySet());
        }
        if (propSetters != null) {
            keys.addAll(propSetters.keySet());
        }
        return keys;
    }

    public List<JsValue> getElements() {
        return elements;
    }
}
