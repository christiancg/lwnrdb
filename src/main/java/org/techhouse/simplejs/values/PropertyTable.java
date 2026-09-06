package org.techhouse.simplejs.values;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.techhouse.simplejs.values.JsObject.PropertyFlags;

// The ordinary-object substrate: own data properties, accessors, per-key attribute flags and
// extensibility, shared by every value type that has an own-property surface. Exotic types keep
// their own behaviour (array indices, typed-array canonical numeric indices, the global
// Environment) in front of this and delegate everything else to it.
public final class PropertyTable {
    private static final long MAX_ARRAY_INDEX = 4294967294L;
    private static final int MAX_INDEX_KEY_LENGTH = 10;

    private final Map<String, JsValue> properties = new LinkedHashMap<>();
    // Data properties and accessors live in separate maps, so their relative insertion order is only
    // recoverable from this single own-key list.
    private final Set<String> keyOrder = new LinkedHashSet<>();
    private boolean extensible = true;
    private Map<String, JsValue> accessorGetters;
    private Map<String, JsValue> accessorSetters;
    // The source of truth for "is this key an accessor property", independent of whether either
    // side actually holds a function - a descriptor like {get: undefined, set: undefined} is still
    // a genuine accessor property per spec (reads as undefined, rejects writes), not a data
    // property, even though neither accessor map above ever gets an entry for it.
    private Set<String> accessorKeys;
    private Map<String, PropertyFlags> descriptors;
    private Set<JsSymbol> symbolKeyOrder;
    private Map<JsSymbol, JsValue> symbolProperties;
    private Map<JsSymbol, PropertyFlags> symbolFlags;
    private Map<JsSymbol, JsValue> symbolAccessorGetters;
    private Map<JsSymbol, JsValue> symbolAccessorSetters;
    private Set<JsSymbol> symbolAccessorKeys;

    public JsValue get(String key) {
        final var value = properties.get(key);
        return value == null ? JsUndefined.getInstance() : value;
    }

    public boolean set(String key, JsValue value) {
        // An accessor key has no value slot: writing one here would install a data property that
        // shadows the getter/setter pair for every later read.
        if (hasAccessor(key)) {
            return false;
        }
        if (properties.containsKey(key)) {
            if (isWritable(key)) {
                properties.put(key, value);
                keyOrder.add(key);
                return true;
            }
            return false;
        }
        if (extensible) {
            properties.put(key, value);
            keyOrder.add(key);
            return true;
        }
        return false;
    }

    public void defineValue(String key, JsValue value) {
        properties.put(key, value);
        keyOrder.add(key);
    }

    public boolean has(String key) {
        return properties.containsKey(key);
    }

    public boolean delete(String key) {
        if (keyOrder.contains(key) && isNotConfigurable(key)) {
            return false;
        }
        properties.remove(key);
        keyOrder.remove(key);
        if (accessorGetters != null) {
            accessorGetters.remove(key);
        }
        if (accessorSetters != null) {
            accessorSetters.remove(key);
        }
        if (accessorKeys != null) {
            accessorKeys.remove(key);
        }
        if (descriptors != null) {
            descriptors.remove(key);
        }
        return true;
    }

    public PropertyFlags getFlags(String key) {
        if (descriptors == null) {
            return PropertyFlags.DEFAULT;
        }
        final var flags = descriptors.get(key);
        return flags == null ? PropertyFlags.DEFAULT : flags;
    }

    public void setFlags(String key, PropertyFlags flags) {
        if (descriptors == null) {
            descriptors = new LinkedHashMap<>();
        }
        descriptors.put(key, flags);
    }

    public boolean isWritable(String key) {
        return getFlags(key).writable();
    }

    public boolean isEnumerable(String key) {
        return getFlags(key).enumerable();
    }

    public boolean isNotConfigurable(String key) {
        return !getFlags(key).configurable();
    }

    public boolean isExtensible() {
        return extensible;
    }

    public void preventExtensions() {
        extensible = false;
    }

    public void seal() {
        extensible = false;
        for (final var key : keys()) {
            final var flags = getFlags(key);
            setFlags(key, new PropertyFlags(flags.writable(), flags.enumerable(), false));
        }
        for (final var symbol : symbolKeys()) {
            final var flags = getSymbolFlags(symbol);
            setSymbolFlags(symbol, new PropertyFlags(flags.writable(), flags.enumerable(), false));
        }
    }

    public void freeze() {
        extensible = false;
        for (final var key : keys()) {
            setFlags(key, new PropertyFlags(false, isEnumerable(key), false));
        }
        for (final var symbol : symbolKeys()) {
            final var flags = getSymbolFlags(symbol);
            setSymbolFlags(symbol, new PropertyFlags(false, flags.enumerable(), false));
        }
    }

    public boolean isFrozen() {
        if (extensible) {
            return false;
        }
        for (final var key : keys()) {
            final var flags = getFlags(key);
            if (flags.configurable() || (flags.writable() && !hasAccessor(key))) {
                return false;
            }
        }
        return true;
    }

    public boolean isSealed() {
        if (extensible) {
            return false;
        }
        for (final var key : keys()) {
            if (getFlags(key).configurable()) {
                return false;
            }
        }
        return true;
    }

    public Set<String> keys() {
        return orderKeys(keyOrder);
    }

    // OrdinaryOwnPropertyKeys: canonical array-index keys ascending, then the rest in insertion order
    private static Set<String> orderKeys(Collection<String> raw) {
        final var indexes = new ArrayList<String>();
        for (final var key : raw) {
            if (isArrayIndexKey(key)) {
                indexes.add(key);
            }
        }
        if (indexes.isEmpty()) {
            return new LinkedHashSet<>(raw);
        }
        indexes.sort(Comparator.comparingLong(Long::parseLong));
        final var ordered = new LinkedHashSet<>(indexes);
        for (final var key : raw) {
            if (!isArrayIndexKey(key)) {
                ordered.add(key);
            }
        }
        return ordered;
    }

    private static boolean isArrayIndexKey(String key) {
        if (key.isEmpty() || key.length() > MAX_INDEX_KEY_LENGTH || (key.length() > 1 && key.charAt(0) == '0')) {
            return false;
        }
        for (var i = 0; i < key.length(); i++) {
            if (!Character.isDigit(key.charAt(i))) {
                return false;
            }
        }
        return Long.parseLong(key) <= MAX_ARRAY_INDEX;
    }

    public Map<String, JsValue> getProperties() {
        return properties;
    }

    public JsValue getSymbol(JsSymbol key) {
        final var value = symbolProperties == null ? null : symbolProperties.get(key);
        return value == null ? JsUndefined.getInstance() : value;
    }

    public boolean setSymbol(JsSymbol key, JsValue value) {
        final var isNew = symbolProperties == null || !symbolProperties.containsKey(key);
        if (isNew && !extensible) {
            return false;
        }
        // A symbol-keyed data property honours [[Writable]] exactly like a string-keyed one, so
        // Object.freeze(o) rejects `o[sym] = v` rather than silently letting it through.
        if (!isNew && !getSymbolFlags(key).writable()) {
            return false;
        }
        if (symbolProperties == null) {
            symbolProperties = new LinkedHashMap<>();
        }
        symbolProperties.put(key, value);
        registerSymbolKey(key);
        return true;
    }

    // [[DefineOwnProperty]]'s counterpart to setSymbol: a definition is not an assignment, so it is
    // not subject to [[Writable]] (a non-writable but configurable symbol property can be redefined).
    public void defineSymbolValue(JsSymbol key, JsValue value) {
        if (symbolProperties == null) {
            symbolProperties = new LinkedHashMap<>();
        }
        symbolProperties.put(key, value);
        registerSymbolKey(key);
    }

    public boolean hasSymbol(JsSymbol key) {
        return symbolProperties != null && symbolProperties.containsKey(key);
    }

    public boolean isNotDeleteSymbol(JsSymbol key) {
        if (symbolKeyOrder == null || !symbolKeyOrder.contains(key)) {
            return false;
        }
        if (!getSymbolFlags(key).configurable()) {
            return true;
        }
        symbolKeyOrder.remove(key);
        if (symbolProperties != null) {
            symbolProperties.remove(key);
        }
        if (symbolFlags != null) {
            symbolFlags.remove(key);
        }
        if (symbolAccessorGetters != null) {
            symbolAccessorGetters.remove(key);
        }
        if (symbolAccessorSetters != null) {
            symbolAccessorSetters.remove(key);
        }
        if (symbolAccessorKeys != null) {
            symbolAccessorKeys.remove(key);
        }
        return false;
    }

    public Set<JsSymbol> symbolKeys() {
        return symbolKeyOrder == null ? Set.of() : symbolKeyOrder;
    }

    public void setSymbolFlags(JsSymbol key, PropertyFlags flags) {
        if (symbolFlags == null) {
            symbolFlags = new LinkedHashMap<>();
        }
        symbolFlags.put(key, flags);
    }

    public PropertyFlags getSymbolFlags(JsSymbol key) {
        final var flags = symbolFlags == null ? null : symbolFlags.get(key);
        return flags == null ? PropertyFlags.DEFAULT : flags;
    }

    public void defineAccessor(String key, JsValue getter, JsValue setter) {
        // Always registers the key as a genuine accessor property, even when both getter and
        // setter are null (e.g. a defineProperty descriptor of {get: undefined, set: undefined}):
        // per spec that is still an accessor, not a data property, just one whose sides both read
        // as undefined and reject writes. A key transitioning from a data property to an accessor
        // (e.g. a class installing `static set length(_) {}` over the constructor's own default
        // "length" value) must drop the stale data value - has()/get() and the accessor registry
        // are meant to be mutually exclusive for one key.
        keyOrder.add(key);
        properties.remove(key);
        registerAccessorKey(key);
        if (getter != null) {
            if (accessorGetters == null) {
                accessorGetters = new LinkedHashMap<>();
            }
            accessorGetters.put(key, getter);
        }
        if (setter != null) {
            if (accessorSetters == null) {
                accessorSetters = new LinkedHashMap<>();
            }
            accessorSetters.put(key, setter);
        }
    }

    public JsValue getAccessorGetter(String key) {
        return accessorGetters == null ? null : accessorGetters.get(key);
    }

    public JsValue getAccessorSetter(String key) {
        return accessorSetters == null ? null : accessorSetters.get(key);
    }

    // Used when a redefine converts an accessor property into a data property: the accessor
    // entries must not linger, or a later read would still find the stale getter/setter.
    public void clearAccessor(String key) {
        clearAccessorGetter(key);
        clearAccessorSetter(key);
        if (accessorKeys != null) {
            accessorKeys.remove(key);
        }
    }

    // defineAccessor is intentionally additive (a null side is "leave as-is", relied on by object
    // literals installing a getter and setter via two separate calls) - these let a caller that
    // means "explicitly remove this side" (e.g. a defineProperty redefine with `get: undefined`)
    // say so without also wiping the side it isn't touching.
    public void clearAccessorGetter(String key) {
        if (accessorGetters != null) {
            accessorGetters.remove(key);
        }
    }

    public void clearAccessorSetter(String key) {
        if (accessorSetters != null) {
            accessorSetters.remove(key);
        }
    }

    public void defineSymbolAccessor(JsSymbol key, JsValue getter, JsValue setter) {
        // Mirrors defineAccessor's string-key behaviour: always registers the symbol as a genuine
        // accessor, even when both sides are null.
        registerSymbolKey(key);
        registerSymbolAccessorKey(key);
        if (getter != null) {
            if (symbolAccessorGetters == null) {
                symbolAccessorGetters = new LinkedHashMap<>();
            }
            symbolAccessorGetters.put(key, getter);
        }
        if (setter != null) {
            if (symbolAccessorSetters == null) {
                symbolAccessorSetters = new LinkedHashMap<>();
            }
            symbolAccessorSetters.put(key, setter);
        }
    }

    public JsValue getSymbolAccessorGetter(JsSymbol key) {
        return symbolAccessorGetters == null ? null : symbolAccessorGetters.get(key);
    }

    public JsValue getSymbolAccessorSetter(JsSymbol key) {
        return symbolAccessorSetters == null ? null : symbolAccessorSetters.get(key);
    }

    public void clearSymbolAccessorGetter(JsSymbol key) {
        if (symbolAccessorGetters != null) {
            symbolAccessorGetters.remove(key);
        }
    }

    public void clearSymbolAccessorSetter(JsSymbol key) {
        if (symbolAccessorSetters != null) {
            symbolAccessorSetters.remove(key);
        }
    }

    public void clearSymbolAccessor(JsSymbol key) {
        clearSymbolAccessorGetter(key);
        clearSymbolAccessorSetter(key);
        if (symbolAccessorKeys != null) {
            symbolAccessorKeys.remove(key);
        }
    }

    public boolean hasSymbolAccessor(JsSymbol key) {
        return symbolAccessorKeys != null && symbolAccessorKeys.contains(key);
    }

    public boolean hasAccessor(String key) {
        return accessorKeys != null && accessorKeys.contains(key);
    }

    private void registerAccessorKey(String key) {
        if (accessorKeys == null) {
            accessorKeys = new LinkedHashSet<>();
        }
        accessorKeys.add(key);
    }

    private void registerSymbolKey(JsSymbol key) {
        if (symbolKeyOrder == null) {
            symbolKeyOrder = new LinkedHashSet<>();
        }
        symbolKeyOrder.add(key);
    }

    private void registerSymbolAccessorKey(JsSymbol key) {
        if (symbolAccessorKeys == null) {
            symbolAccessorKeys = new LinkedHashSet<>();
        }
        symbolAccessorKeys.add(key);
    }
}
