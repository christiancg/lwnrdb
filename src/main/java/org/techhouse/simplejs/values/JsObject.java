package org.techhouse.simplejs.values;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class JsObject extends JsValue {
    public record PropertyFlags(boolean writable, boolean enumerable, boolean configurable) {
        public static final PropertyFlags DEFAULT = new PropertyFlags(true, true, true);
    }

    private static final long MAX_ARRAY_INDEX = 4294967294L;
    private static final int MAX_INDEX_KEY_LENGTH = 10;

    private final Map<String, JsValue> properties = new LinkedHashMap<>();
    // Data properties and accessors live in separate maps, so their relative insertion order is only
    // recoverable from this single own-key list.
    private final Set<String> keyOrder = new LinkedHashSet<>();
    private boolean extensible = true;
    private boolean errorData;
    private JsClass klass;
    private Map<String, JsValue> privateFields;
    private Map<JsSymbol, JsValue> symbolProperties;
    private Map<JsSymbol, PropertyFlags> symbolFlags;
    private JsObject proto;
    private Map<String, JsValue> accessorGetters;
    private Map<String, JsValue> accessorSetters;
    private Map<JsSymbol, JsValue> symbolAccessorGetters;
    private Map<JsSymbol, JsValue> symbolAccessorSetters;
    private Map<String, PropertyFlags> descriptors;
    private JsValue primitive;

    public JsValue get(String key) {
        final var value = properties.get(key);
        return value == null ? JsUndefined.getInstance() : value;
    }

    public boolean set(String key, JsValue value) {
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
    }

    public void freeze() {
        extensible = false;
        for (final var key : keys()) {
            setFlags(key, new PropertyFlags(false, isEnumerable(key), false));
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

    public boolean isErrorData() {
        return errorData;
    }

    public void markErrorData() {
        errorData = true;
    }

    public JsClass getKlass() {
        return klass;
    }

    public void setKlass(JsClass klass) {
        this.klass = klass;
    }

    public JsValue getPrivate(String key) {
        return privateFields == null ? null : privateFields.get(key);
    }

    public void setPrivate(String key, JsValue value) {
        if (privateFields == null) {
            privateFields = new LinkedHashMap<>();
        }
        privateFields.put(key, value);
    }

    public boolean hasPrivate(String key) {
        return privateFields != null && privateFields.containsKey(key);
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
        if (symbolProperties == null) {
            symbolProperties = new LinkedHashMap<>();
        }
        symbolProperties.put(key, value);
        return true;
    }

    public boolean hasSymbol(JsSymbol key) {
        return symbolProperties != null && symbolProperties.containsKey(key);
    }

    public boolean isNotDeleteSymbol(JsSymbol key) {
        if (symbolProperties == null || !symbolProperties.containsKey(key)) {
            return false;
        }
        if (!getSymbolFlags(key).configurable()) {
            return true;
        }
        symbolProperties.remove(key);
        if (symbolFlags != null) {
            symbolFlags.remove(key);
        }
        if (symbolAccessorGetters != null) {
            symbolAccessorGetters.remove(key);
        }
        if (symbolAccessorSetters != null) {
            symbolAccessorSetters.remove(key);
        }
        return false;
    }

    public Set<JsSymbol> symbolKeys() {
        return symbolProperties == null ? Set.of() : symbolProperties.keySet();
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

    public JsValue getPrimitive() {
        return primitive;
    }

    public void setPrimitive(JsValue primitive) {
        this.primitive = primitive;
    }

    public JsObject getProto() {
        return proto;
    }

    public void setProto(JsObject proto) {
        this.proto = proto;
    }

    public void defineAccessor(String key, JsValue getter, JsValue setter) {
        if (getter != null || setter != null) {
            keyOrder.add(key);
        }
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

    public boolean hasSymbolAccessor(JsSymbol key) {
        return (symbolAccessorGetters != null && symbolAccessorGetters.containsKey(key))
                || (symbolAccessorSetters != null && symbolAccessorSetters.containsKey(key));
    }

    public boolean hasAccessor(String key) {
        return (accessorGetters != null && accessorGetters.containsKey(key))
                || (accessorSetters != null && accessorSetters.containsKey(key));
    }
}
