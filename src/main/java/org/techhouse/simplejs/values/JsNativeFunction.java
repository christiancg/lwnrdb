package org.techhouse.simplejs.values;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;

public final class JsNativeFunction extends JsValue implements JsCallableProperties {
    private static final ThreadLocal<JsValue> NEW_TARGET = new ThreadLocal<>();

    private final String name;
    private final BiFunction<JsValue, List<JsValue>, JsValue> implementation;
    private final PropertyTable table = new PropertyTable();
    private Set<String> deletedMetadataKeys;
    private JsValue boundTarget;
    private List<JsValue> boundArgs;
    private JsValue prototype;
    private JsValue ownProto;
    private double explicitLength = -1;
    private boolean constructor;

    public JsNativeFunction(String name, BiFunction<JsValue, List<JsValue>, JsValue> implementation) {
        this.name = name;
        this.implementation = implementation;
    }

    // [[Construct]] is an explicit, immutable-once-set bit rather than a consequence of
    // `prototype` being non-null: a script may assign `Array.from.prototype = {}` through the
    // ordinary member seam, which must not turn a non-constructor builtin into one.
    public boolean isConstructor() {
        return constructor;
    }

    public void markConstructor() {
        constructor = true;
    }

    // Overrides the "length" metadata FunctionProtoBuiltins would otherwise report (0, since a
    // native's real parameter count isn't reflectively available) while keeping it non-writable,
    // non-enumerable, configurable - the spec shape for a builtin's length - by never putting it in
    // the mutable property map.
    public void setLength(int length) {
        this.explicitLength = length;
    }

    // BoundFunctionLength can yield +Infinity or a value past the int range, so the slot itself is a
    // double; getExplicitLength keeps its int shape for the builtin-length wiring, which never sees one.
    public void setLength(double length) {
        this.explicitLength = length;
    }

    public boolean hasExplicitLength() {
        return explicitLength >= 0;
    }

    public int getExplicitLength() {
        return (int) explicitLength;
    }

    public double getExplicitLengthValue() {
        return explicitLength;
    }

    public String getName() {
        return name;
    }

    public void setBound(JsValue boundTarget, List<JsValue> boundArgs) {
        this.boundTarget = boundTarget;
        this.boundArgs = boundArgs;
    }

    public boolean isBound() {
        return boundTarget != null;
    }

    public JsValue getBoundTarget() {
        return boundTarget;
    }

    public List<JsValue> getBoundArgs() {
        return boundArgs;
    }

    // The new.target of the [[Construct]] currently running, or null when this is a plain call - the
    // only signal a native constructor has that it was reached through `new`.
    public static JsValue currentNewTarget() {
        return NEW_TARGET.get();
    }

    public JsValue invoke(JsValue thisArg, List<JsValue> args) {
        final var previous = NEW_TARGET.get();
        if (previous == null) {
            return implementation.apply(thisArg, args);
        }
        NEW_TARGET.remove();
        try {
            return implementation.apply(thisArg, args);
        } finally {
            NEW_TARGET.set(previous);
        }
    }

    public JsValue invoke(JsValue thisArg, List<JsValue> args, JsValue newTarget) {
        final var previous = NEW_TARGET.get();
        NEW_TARGET.set(newTarget);
        try {
            return implementation.apply(thisArg, args);
        } finally {
            if (previous == null) {
                NEW_TARGET.remove();
            } else {
                NEW_TARGET.set(previous);
            }
        }
    }

    @Override
    public PropertyTable ownProperties() {
        return table;
    }

    @Override
    public void setProperty(String key, JsValue value) {
        table.defineValue(key, value);
        table.setFlags(key, HIDDEN);
    }

    @Override
    public void setEnumerableProperty(String key, JsValue value) {
        table.set(key, value);
    }

    @Override
    public JsValue getProperty(String key) {
        return table.has(key) ? table.get(key) : null;
    }

    @Override
    public boolean hasProperty(String key) {
        return table.has(key);
    }

    @Override
    public boolean deleteProperty(String key) {
        return table.delete(key);
    }

    // See JsFunction.deleteOwnProperty for why this override exists: the generic JsValue path
    // (reached via a no-trap Proxy forwarding a delete here) never calls markMetadataDeleted, so
    // hasOwnProperty would keep reporting "name"/"length" present regardless of what the table
    // itself holds. "prototype" is rejected outright when this native function actually carries one
    // (always non-configurable).
    @Override
    public boolean deleteOwnProperty(JsValue key) {
        if (key instanceof JsSymbol) {
            return super.deleteOwnProperty(key);
        }
        final var name = OrdinaryProperties.keyName(key);
        if (("name".equals(name) || "length".equals(name)) && !hasProperty(name)) {
            markMetadataDeleted(name);
            return true;
        }
        if ("prototype".equals(name) && !hasProperty(name) && getPrototype() != null) {
            return false;
        }
        return super.deleteOwnProperty(key);
    }

    @Override
    public void markMetadataDeleted(String key) {
        if (deletedMetadataKeys == null) {
            deletedMetadataKeys = new LinkedHashSet<>();
        }
        deletedMetadataKeys.add(key);
    }

    @Override
    public boolean isMetadataDeleted(String key) {
        return deletedMetadataKeys != null && deletedMetadataKeys.contains(key);
    }

    @Override
    public List<String> propertyKeys() {
        return new ArrayList<>(table.keys());
    }

    @Override
    public List<String> enumerablePropertyKeys() {
        return table.keys().stream().filter(table::isEnumerable).toList();
    }

    public JsValue getPrototype() {
        return prototype;
    }

    public void setPrototype(JsValue prototype) {
        this.prototype = prototype;
    }

    // The own [[Prototype]] this function object reports to Object.getPrototypeOf/instanceof-chain
    // walks - distinct from `prototype` above (the object `new` links instances to). Null falls
    // back to the generic Function.prototype-equivalent shared by every native function; a native
    // superclass constructor (e.g. %TypedArray% for the concrete typed array constructors) sets it
    // to model the real spec constructor-level inheritance chain.
    public JsValue getOwnProto() {
        return ownProto;
    }

    public void setOwnProto(JsValue ownProto) {
        this.ownProto = ownProto;
    }
}
