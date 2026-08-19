package org.techhouse.simplejs.values;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;

public final class JsArray extends JsValue {
    private static final JsValue HOLE = JsUndefined.getHole();
    // Elements at or beyond this cap are never materialised one slot per index - they are held in
    // `sparseValues` instead (or, past Integer.MAX_VALUE, as an ordinary named property; see
    // defineOwnProperty) - so a spec-legal length up to MAX_ARRAY_LENGTH never requires padding a
    // dense list hole by hole all the way out.
    private static final int MAX_DENSE_LENGTH = 1 << 24;
    // ECMA-262 array index/length ceiling: 2^32 - 1. A property named "4294967295" is not a canonical
    // array index (ToUint32(P) must not equal 2^32-1), so this doubles as the maximum legal `length`.
    public static final long MAX_ARRAY_LENGTH = 4_294_967_295L;

    private final List<JsValue> elements = new ArrayList<>();
    private final PropertyTable table = new PropertyTable();
    // Indices at or beyond MAX_DENSE_LENGTH but still addressable as a Java int (i.e. <= Integer.MAX_VALUE)
    // that have been explicitly set - reached via the same int-keyed get/set/isHole surface the dense
    // region uses, so every existing caller (MemberEvaluator's array fast path, ArrayLike, etc.) keeps
    // working unmodified while indices up to ~2^31 no longer hit the old hard dense cap. Indices beyond
    // Integer.MAX_VALUE (up to MAX_ARRAY_LENGTH) are never reachable through this map or through get/set
    // at all - defineOwnProperty is the only path that can address them (Object.defineProperty with a
    // literal huge index/length), and it stores the value as an ordinary named property in `table`
    // instead, since no int-keyed fast path could ever look it up anyway.
    private Map<Integer, JsValue> sparseValues;
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
    // The canonical array length (ArraySetLength's stored value). Decoupled from elements.size() once
    // it grows past MAX_DENSE_LENGTH; kept equal to elements.size() below that cap (see truncateTo),
    // so every reader that still assumes "elements.size() == length" for an ordinarily-sized array
    // (spread, enumeration, removeHoles, ...) keeps seeing exactly the value it always has.
    private long length;

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
        length = initial.size();
    }

    // True when a plain value (not a hole, not an accessor-only slot) is physically present at this
    // index, whether in the dense list or the sparse overflow map.
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

    // "No own value lives here" - true both for an explicit hole *and* for an index at or past the
    // current length, so every caller that means "is this index absent" (getOwnProperty,
    // deleteOwnProperty, the indexSlot used by defineOwnProperty, and MemberEvaluator's own
    // `index < array.length() && !array.isHole(index)` checks) can rely on this method alone instead
    // of also re-deriving the out-of-range case itself.
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
                padTo((int) newLength);
            }
            length = newLength;
        }
    }

    // Backs `delete arr[i]` on a configurable index: the slot becomes a genuine hole (no longer an
    // own property) rather than merely holding undefined, and any accessor/flag override is dropped
    // so a later plain assignment to the same index starts from the ordinary defaults again.
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
        // `length` is a separate field once it can outgrow `elements` (see the class-level doc
        // comment), so compacting the dense list must shrink it by the same amount explicitly -
        // it is never re-derived from elements.size() automatically.
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

    // [[DefineOwnProperty]] bypass: unlike set(), this ignores frozen/writable/extensible so
    // Object.defineProperty can place a value regardless of the slot's current flags (the caller is
    // responsible for the extensibility/configurability checks the spec performs beforehand).
    public void defineIndexValue(int index, JsValue value) {
        storeIndexValue(index, value);
    }

    // Shared by set() and defineIndexValue(): below the dense cap the value lands in the padded list
    // (exactly as before), at or beyond it in the sparse overflow map - either way the canonical
    // length grows to cover it, matching ArraySetLength/CreateDataProperty's implicit length bump.
    private void storeIndexValue(int index, JsValue value) {
        if (index < MAX_DENSE_LENGTH) {
            padToIndex(index);
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

    // Full-width counterpart to set(int, JsValue): the ordinary [[Set]] fast path
    // (MemberEvaluator.setArrayMember) recognises the same [0, MAX_ARRAY_LENGTH) canonical index
    // range as canonicalArrayIndexWide, so an index past Integer.MAX_VALUE - which can never live in
    // the int-keyed elements/sparseValues storage - is kept as an ordinary named property in `table`
    // instead (mirroring defineOwnProperty's wide branch), while still enforcing frozen/extensible/
    // writable exactly like set(int) does.
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

    public long length() {
        return length;
    }

    public boolean setLength(long newLength) {
        if (frozen || (sealed && newLength != length)) {
            return false;
        }
        if (!table.isExtensible() && newLength > length) {
            return false;
        }
        // OrdinarySet rejects a write to a non-writable data property even when the value is
        // unchanged, so `array.length = array.length` on a frozen length still fails.
        if (!lengthFlags.writable()) {
            return false;
        }
        return truncateTo(newLength);
    }

    // ArraySetLength steps 16-17: the tail is deleted one index at a time in descending order and the
    // walk stops at the first non-configurable index, leaving length just above it and answering false.
    // A wide (> Integer.MAX_VALUE) index setWideIndex stored as an ordinary named property in `table`
    // is always numerically above every sparse one, which is in turn always above every dense one, so
    // walking wide-then-sparse-then-dense is exactly the spec's single descending walk, never a mix.
    private boolean truncateTo(long newLength) {
        if (newLength < length) {
            if (!removeWideTailDown(newLength)) {
                return false;
            }
            if (!removeSparseTailDown(newLength)) {
                return false;
            }
            if (!removeDenseTailDown(newLength)) {
                return false;
            }
        }
        length = newLength;
        if (length <= MAX_DENSE_LENGTH) {
            padTo((int) length);
        }
        return true;
    }

    // The counterpart to removeSparseTailDown for indices setWideIndex stored past Integer.MAX_VALUE
    // as ordinary named properties (never in sparseValues) - table.keys() is scanned rather than
    // walked from a maintained index set because a wide index is expected to be rare.
    private boolean removeWideTailDown(long newLength) {
        final var wideKeys = new ArrayList<Long>();
        for (final var key : table.keys()) {
            final var wide = InterpreterUtils.canonicalArrayIndexWide(key);
            if (wide != null && wide > Integer.MAX_VALUE) {
                wideKeys.add(wide);
            }
        }
        if (wideKeys.isEmpty()) {
            return true;
        }
        wideKeys.sort(Collections.reverseOrder());
        for (final var wideKey : wideKeys) {
            if (wideKey < newLength) {
                continue;
            }
            final var name = Long.toString(wideKey);
            if (!getPropFlags(name).configurable()) {
                length = wideKey + 1L;
                return false;
            }
            deleteProperty(name);
        }
        return true;
    }

    private boolean removeSparseTailDown(long newLength) {
        if (sparseValues == null || sparseValues.isEmpty()) {
            return true;
        }
        final var keys = new ArrayList<>(sparseValues.keySet());
        keys.sort(Collections.reverseOrder());
        for (final var key : keys) {
            if (key < newLength) {
                continue;
            }
            if (!getIndexFlags(key).configurable()) {
                length = key + 1L;
                return false;
            }
            sparseValues.remove(key);
            clearIndexAccessor(key);
            if (indexFlags != null) {
                indexFlags.remove(key);
            }
        }
        return true;
    }

    private boolean removeDenseTailDown(long newLength) {
        while (elements.size() > newLength) {
            final var last = elements.size() - 1;
            if (ownsIndex(last) && !getIndexFlags(last).configurable()) {
                length = elements.size();
                return false;
            }
            clearIndexAccessor(last);
            if (indexFlags != null) {
                indexFlags.remove(last);
            }
            elements.removeLast();
        }
        return true;
    }

    public void setLengthWritable(boolean writable) {
        lengthFlags = new JsObject.PropertyFlags(writable, lengthFlags.enumerable(), false);
    }

    // [[DefineOwnProperty]] on "length" bypasses the writable check for the value itself (only a
    // later [[Set]] respects it), matching ArraySetLength's "set newLenDesc's [[Value]] first, then
    // apply the writable attribute" order.
    public boolean defineLength(long newLength) {
        return truncateTo(newLength);
    }

    private void padToIndex(int index) {
        padTo(index + 1);
    }

    private void padTo(int denseLength) {
        while (elements.size() < denseLength) {
            elements.add(HOLE);
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
        // Sparse (>= MAX_DENSE_LENGTH) indices are always numerically above every dense one, so a
        // sorted append after the dense loop keeps the canonical ascending-index ordering intact.
        if (sparseValues != null && !sparseValues.isEmpty()) {
            final var sparseKeys = new ArrayList<>(sparseValues.keySet());
            sparseKeys.sort(null);
            for (final var sparseKey : sparseKeys) {
                keys.add(new JsString(Integer.toString(sparseKey)));
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
            return PropertyDescriptor.data(new JsNumber(length), lengthFlags);
        }
        final var index = InterpreterUtils.arrayIndex(name);
        if (index != null) {
            if (hasIndexAccessor(index)) {
                return PropertyDescriptor.accessor(getIndexAccessorGetter(index), getIndexAccessorSetter(index),
                        getIndexFlags(index));
            }
            return isHole(index) ? null : PropertyDescriptor.data(get(index), getIndexFlags(index));
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
        // arrayIndex (Integer, int range only) covers the fast path every other array-index consumer
        // in the interpreter also uses; canonicalArrayIndexWide additionally recognises an index at or
        // past 2^31 (Integer.parseInt's overflow point) so a huge literal index/length reaching
        // Object.defineProperty/defineProperties directly - the only paths that call here rather than
        // through the int-bounded fast paths - still updates "length" per ArrayDefineOwnProperty step 4.
        final var wideIndex = InterpreterUtils.canonicalArrayIndexWide(name);
        if (wideIndex == null) {
            return super.defineOwnProperty(key, descriptor);
        }
        // ArrayDefineOwnProperty step 4.d: an index at or past the current length can't be added
        // (growing the array) while "length" itself is non-writable.
        if (wideIndex >= length && !lengthFlags.writable()) {
            throw new TypeErrorException("Cannot define property " + name + ", length is not writable");
        }
        if (wideIndex <= Integer.MAX_VALUE) {
            OrdinaryProperties.validateAndApply(indexSlot(wideIndex.intValue()), table.isExtensible(), name,
                    descriptor);
        } else {
            // Beyond Integer.MAX_VALUE, no int-keyed storage can ever address this index anyway (every
            // other consumer reaches an array index through arrayIndex's int-only fast path, so it
            // already treats a key this large as an ordinary named property) - store it the same way,
            // as an ordinary property, and only apply the array-specific length bump below.
            super.defineOwnProperty(key, descriptor);
        }
        if (wideIndex + 1 > length) {
            length = wideIndex + 1;
        }
        return true;
    }

    // Array indices go through the same ValidateAndApplyPropertyDescriptor the ordinary string/symbol
    // properties use (OrdinaryProperties), instead of a bespoke duplicate: the array's own copy had
    // drifted (missing the accessor-identity compatibility check entirely, and never handling a
    // generic descriptor that only touches enumerable/configurable on an existing accessor).
    private OrdinaryProperties.Slot indexSlot(int index) {
        return new OrdinaryProperties.Slot() {
            @Override
            public boolean exists() {
                return ownsIndex(index);
            }

            @Override
            public boolean hasAccessor() {
                return hasIndexAccessor(index);
            }

            @Override
            public JsObject.PropertyFlags flags() {
                return getIndexFlags(index);
            }

            @Override
            public void setFlags(JsObject.PropertyFlags flags) {
                setIndexFlags(index, flags);
            }

            @Override
            public JsValue value() {
                return get(index);
            }

            @Override
            public boolean hasValue() {
                return !hasIndexAccessor(index) && !isHole(index);
            }

            @Override
            public void defineValue(JsValue value) {
                defineIndexValue(index, value);
            }

            @Override
            public void removeValue() {
                defineIndexValue(index, JsUndefined.getInstance());
            }

            @Override
            public JsValue getter() {
                return getIndexAccessorGetter(index);
            }

            @Override
            public JsValue setter() {
                return getIndexAccessorSetter(index);
            }

            @Override
            public void defineAccessor(JsValue getter, JsValue setter) {
                defineIndexAccessor(index, getter, setter);
            }

            @Override
            public void clearGetter() {
                if (indexGetters != null) {
                    indexGetters.remove(index);
                }
            }

            @Override
            public void clearSetter() {
                if (indexSetters != null) {
                    indexSetters.remove(index);
                }
            }

            @Override
            public void clearAccessor() {
                clearIndexAccessor(index);
            }
        };
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
        if (!lengthFlags.writable() && newLength != length) {
            throw new TypeErrorException("Cannot redefine property: length");
        }
        final var truncated = defineLength(newLength);
        setLengthWritable(writable);
        if (!truncated) {
            throw new TypeErrorException("Cannot redefine property: length");
        }
    }

    private boolean ownsIndex(int index) {
        return (index < length && !isHole(index)) || hasIndexAccessor(index);
    }

    private static long requireArrayLength(JsValue value) {
        final var number = JsCoercion.toNumber(value);
        final var length = (long) number;
        if (length != number || length < 0 || length > MAX_ARRAY_LENGTH) {
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
        // "length" is never absent (unlike every other own key checked below, which falls through to
        // "not present, so deletion trivially succeeds"), and it is always non-configurable, so a
        // delete of it must always fail rather than reporting success by never being reached.
        if ("length".equals(name)) {
            return lengthFlags.configurable();
        }
        final var index = InterpreterUtils.arrayIndex(name);
        if (index != null) {
            if (isHole(index)) {
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

    // A handful of foreign call sites (array-literal/spread construction in ExpressionEvaluator, most
    // notably) mutate this list directly rather than going through push()/set() - a shortcut that
    // relied on elements.size() doubling as the array's length before length became its own field.
    // Delegating through this view instead of the bare backing list keeps that shortcut correct (every
    // mutator here is exercised only while building a fresh, ordinarily-sized array, so staying densely
    // backed is exactly right) without having to touch any of those call sites.
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
}
