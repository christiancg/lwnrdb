package org.techhouse.simplejs.values;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;

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
    private JsValue proto;

    public JsArray() {
    }

    @Override
    public PropertyTable ownProperties() {
        return table;
    }

    // An array is an ordinary object apart from its indices and length, so it owns a [[Prototype]]
    // slot: Object.setPrototypeOf(arr, o) has to redirect inherited reads and index-write setters.
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
        // OrdinarySet rejects a write to a non-writable data property even when the value is
        // unchanged, so `array.length = array.length` on a frozen length still fails.
        if (!lengthFlags.writable()) {
            return false;
        }
        return truncateTo(length);
    }

    // ArraySetLength steps 16-17: the tail is deleted one index at a time in descending order and the
    // walk stops at the first non-configurable index, leaving length just above it and answering false.
    private boolean truncateTo(int length) {
        while (elements.size() > length) {
            final var last = elements.size() - 1;
            if (ownsIndex(last) && !getIndexFlags(last).configurable()) {
                return false;
            }
            clearIndexAccessor(last);
            if (indexFlags != null) {
                indexFlags.remove(last);
            }
            elements.removeLast();
        }
        padTo(length);
        return true;
    }

    public void setLengthWritable(boolean writable) {
        lengthFlags = new JsObject.PropertyFlags(writable, lengthFlags.enumerable(), false);
    }

    // [[DefineOwnProperty]] on "length" bypasses the writable check for the value itself (only a
    // later [[Set]] respects it), matching ArraySetLength's "set newLenDesc's [[Value]] first, then
    // apply the writable attribute" order.
    public boolean defineLength(int length) {
        return truncateTo(length);
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

    @Override
    public List<JsValue> ownPropertyKeys() {
        final var keys = new ArrayList<JsValue>();
        for (var i = 0; i < elements.size(); i++) {
            if (!isHole(i)) {
                keys.add(new JsString(Integer.toString(i)));
            }
        }
        // "length" exists from the moment the array is created, so it precedes every later named key
        // in the creation order OrdinaryOwnPropertyKeys reports.
        keys.add(new JsString("length"));
        for (final var key : table.keys()) {
            keys.add(new JsString(key));
        }
        keys.addAll(table.symbolKeys());
        return keys;
    }

    // Only "length" and canonical index keys are exotic on an array; any other key is an ordinary
    // own property and goes through the shared table path.
    @Override
    public PropertyDescriptor getOwnProperty(JsValue key) {
        if (key instanceof JsSymbol) {
            return super.getOwnProperty(key);
        }
        final var name = OrdinaryProperties.keyName(key);
        if ("length".equals(name)) {
            return PropertyDescriptor.data(new JsNumber(elements.size()), lengthFlags);
        }
        final var index = InterpreterUtils.arrayIndex(name);
        if (index != null) {
            if (hasIndexAccessor(index)) {
                return PropertyDescriptor.accessor(getIndexAccessorGetter(index), getIndexAccessorSetter(index),
                        getIndexFlags(index));
            }
            return index >= elements.size() || isHole(index)
                    ? null
                    : PropertyDescriptor.data(get(index), getIndexFlags(index));
        }
        if (hasPropAccessor(name)) {
            return PropertyDescriptor.accessor(getPropAccessorGetter(name), getPropAccessorSetter(name),
                    getPropFlags(name));
        }
        return table.has(name) ? PropertyDescriptor.data(table.get(name), getPropFlags(name)) : null;
    }

    @Override
    public boolean defineOwnProperty(JsValue key, PropertyDescriptor descriptor) {
        if (key instanceof JsSymbol) {
            return super.defineOwnProperty(key, descriptor);
        }
        final var name = OrdinaryProperties.keyName(key);
        if ("length".equals(name)) {
            if (descriptor.isAccessorDescriptor()) {
                throw OrdinaryProperties.redefineError(name);
            }
            defineLengthFrom(name, descriptor);
            return true;
        }
        final var index = InterpreterUtils.arrayIndex(name);
        if (index == null) {
            return super.defineOwnProperty(key, descriptor);
        }
        if (descriptor.isAccessorDescriptor()) {
            defineIndexAccessorFrom(index, descriptor);
        } else {
            defineIndexFrom(index, name, descriptor);
        }
        return true;
    }

    // ArraySetLength: the value is applied before the writable attribute, so a length redefine that
    // also clears writable still takes effect.
    private void defineLengthFrom(String key, PropertyDescriptor descriptor) {
        // ArraySetLength coerces (and range-checks) the new length before any descriptor validation,
        // so an out-of-range value is a RangeError even when the redefine itself is illegal.
        final var newLength = descriptor.value() == null ? null : requireArrayLength(descriptor.value());
        if (!lengthFlags.configurable() && Boolean.TRUE.equals(descriptor.configurable())) {
            throw OrdinaryProperties.redefineError(key);
        }
        if (!lengthFlags.configurable() && descriptor.enumerable() != null
                && descriptor.enumerable() != lengthFlags.enumerable()) {
            throw OrdinaryProperties.redefineError(key);
        }
        final var writable = descriptor.writableOr(lengthFlags.writable());
        if (newLength == null) {
            if (!lengthFlags.writable() && writable) {
                throw OrdinaryProperties.redefineError(key);
            }
            setLengthWritable(writable);
            return;
        }
        if (!lengthFlags.writable() && newLength != elements.size()) {
            throw new TypeErrorException("Cannot redefine property: length");
        }
        final var truncated = defineLength(newLength);
        setLengthWritable(writable);
        if (!truncated) {
            throw new TypeErrorException("Cannot redefine property: length");
        }
    }

    private void defineIndexFrom(int index, String key, PropertyDescriptor descriptor) {
        final var exists = ownsIndex(index);
        final var currentFlags = exists ? getIndexFlags(index) : new JsObject.PropertyFlags(false, false, false);
        if (!exists && !table.isExtensible()) {
            throw new TypeErrorException("Cannot define property " + key + ", object is not extensible");
        }
        if (exists && !currentFlags.configurable()) {
            checkIndexRedefine(index, key, descriptor, currentFlags);
        }
        final var flags = new JsObject.PropertyFlags(descriptor.writableOr(currentFlags.writable()),
                descriptor.enumerableOr(currentFlags.enumerable()),
                descriptor.configurableOr(currentFlags.configurable()));
        clearIndexAccessor(index);
        defineIndexValue(index,
                descriptor.value() != null ? descriptor.value() : exists ? get(index) : JsUndefined.getInstance());
        setIndexFlags(index, flags);
    }

    private void checkIndexRedefine(int index, String key, PropertyDescriptor descriptor,
            JsObject.PropertyFlags currentFlags) {
        if (Boolean.TRUE.equals(descriptor.configurable())) {
            throw OrdinaryProperties.redefineError(key);
        }
        if (descriptor.enumerable() != null && descriptor.enumerable() != currentFlags.enumerable()) {
            throw OrdinaryProperties.redefineError(key);
        }
        if (currentFlags.writable()) {
            return;
        }
        if (Boolean.TRUE.equals(descriptor.writable())) {
            throw OrdinaryProperties.redefineError(key);
        }
        if (descriptor.value() != null && OrdinaryProperties.isNotSameValue(descriptor.value(), get(index))) {
            throw OrdinaryProperties.redefineError(key);
        }
    }

    private void defineIndexAccessorFrom(int index, PropertyDescriptor descriptor) {
        final var exists = ownsIndex(index);
        final var currentFlags = exists ? getIndexFlags(index) : new JsObject.PropertyFlags(false, false, false);
        if (!exists && !table.isExtensible()) {
            throw new TypeErrorException("Cannot define property " + index + ", object is not extensible");
        }
        if (exists && !currentFlags.configurable()) {
            throw OrdinaryProperties.redefineError(String.valueOf(index));
        }
        final var flags = new JsObject.PropertyFlags(currentFlags.writable(),
                descriptor.enumerableOr(currentFlags.enumerable()),
                descriptor.configurableOr(currentFlags.configurable()));
        defineIndexValue(index, JsUndefined.getInstance());
        clearIndexAccessor(index);
        defineIndexAccessor(index, callableOrNull(descriptor.getter()), callableOrNull(descriptor.setter()));
        setIndexFlags(index, flags);
    }

    private static JsValue callableOrNull(JsValue value) {
        return OrdinaryProperties.isCallable(value) ? value : null;
    }

    private boolean ownsIndex(int index) {
        return (index < elements.size() && !isHole(index)) || hasIndexAccessor(index);
    }

    private static int requireArrayLength(JsValue value) {
        final var number = JsCoercion.toNumber(value);
        final var length = (int) number;
        if (length != number || length < 0) {
            throw new RangeErrorException("Invalid array length");
        }
        return length;
    }

    @Override
    public boolean deleteOwnProperty(JsValue key) {
        if (key instanceof JsSymbol) {
            return super.deleteOwnProperty(key);
        }
        final var name = OrdinaryProperties.keyName(key);
        final var index = InterpreterUtils.arrayIndex(name);
        if (index != null) {
            if (index >= elements.size() || isHole(index)) {
                return true;
            }
            if (!getIndexFlags(index).configurable()) {
                return false;
            }
            clearIndexToHole(index);
            return true;
        }
        if (!table.has(name) && !table.hasAccessor(name)) {
            return true;
        }
        if (!getPropFlags(name).configurable()) {
            return false;
        }
        return deleteProperty(name);
    }

    public List<JsValue> getElements() {
        return elements;
    }
}
