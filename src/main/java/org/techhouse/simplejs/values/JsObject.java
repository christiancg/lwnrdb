package org.techhouse.simplejs.values;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.techhouse.simplejs.internal.interpreter.StackCapture;

public final class JsObject extends JsValue {
    public record PropertyFlags(boolean writable, boolean enumerable, boolean configurable) {
        public static final PropertyFlags DEFAULT = new PropertyFlags(true, true, true);
    }

    private final PropertyTable table = new PropertyTable();
    private boolean errorData;
    private List<String> errorStack;
    private JsClass klass;
    private Map<PrivateName, JsValue> privateFields;
    private Set<JsClass> privateBrands;
    private JsValue proto;
    private boolean protoExplicitlyNull;
    private JsValue primitive;

    @Override
    public PropertyTable ownProperties() {
        return table;
    }

    public JsValue get(String key) {
        return table.get(key);
    }

    public boolean set(String key, JsValue value) {
        return table.set(key, value);
    }

    public void defineValue(String key, JsValue value) {
        table.defineValue(key, value);
    }

    public boolean has(String key) {
        return table.has(key);
    }

    public boolean delete(String key) {
        return table.delete(key);
    }

    public PropertyFlags getFlags(String key) {
        return table.getFlags(key);
    }

    public void setFlags(String key, PropertyFlags flags) {
        table.setFlags(key, flags);
    }

    public boolean isEnumerable(String key) {
        return table.isEnumerable(key);
    }

    @Override
    public boolean isExtensible() {
        return table.isExtensible();
    }

    public void preventExtensions() {
        table.preventExtensions();
    }

    public void seal() {
        table.seal();
    }

    public void freeze() {
        table.freeze();
    }

    public boolean isFrozen() {
        return table.isFrozen();
    }

    public boolean isSealed() {
        return table.isSealed();
    }

    public Set<String> keys() {
        return table.keys();
    }

    public Map<String, JsValue> getProperties() {
        return table.getProperties();
    }

    public boolean isErrorData() {
        return errorData;
    }

    public List<String> getErrorStack() {
        return errorStack;
    }

    // Every error object is branded here, so the trace is taken here too rather than at each of the
    // three construction sites: it has to be captured before the Java stack it was thrown on unwinds.
    public void markErrorData() {
        errorData = true;
        errorStack = StackCapture.current();
    }

    public JsClass getKlass() {
        return klass;
    }

    public void setKlass(JsClass klass) {
        this.klass = klass;
    }

    public JsValue getPrivate(PrivateName key) {
        return privateFields == null ? null : privateFields.get(key);
    }

    public void setPrivate(PrivateName key, JsValue value) {
        if (privateFields == null) {
            privateFields = new IdentityHashMap<>();
        }
        privateFields.put(key, value);
    }

    // PrivateFieldAdd: a name already present, or a non-extensible receiver, is a TypeError, which the
    // caller raises from a false return.
    public boolean addPrivate(PrivateName key, JsValue value) {
        if (hasPrivate(key) || !isExtensible()) {
            return false;
        }
        setPrivate(key, value);
        return true;
    }

    public boolean hasPrivate(PrivateName key) {
        return privateFields != null && privateFields.containsKey(key);
    }

    // PrivateBrandAdd: re-branding an object the same class already initialised is a TypeError.
    public boolean addPrivateBrand(JsClass owner) {
        if (privateBrands == null) {
            privateBrands = Collections.newSetFromMap(new IdentityHashMap<>());
        }
        return privateBrands.add(owner);
    }

    public boolean hasPrivateBrand(JsClass owner) {
        return privateBrands != null && privateBrands.contains(owner);
    }

    public JsValue getSymbol(JsSymbol key) {
        return table.getSymbol(key);
    }

    public boolean setSymbol(JsSymbol key, JsValue value) {
        return table.setSymbol(key, value);
    }

    public boolean hasSymbol(JsSymbol key) {
        return table.hasSymbol(key);
    }

    public boolean isNotDeleteSymbol(JsSymbol key) {
        return table.isNotDeleteSymbol(key);
    }

    public Set<JsSymbol> symbolKeys() {
        return table.symbolKeys();
    }

    public void setSymbolFlags(JsSymbol key, PropertyFlags flags) {
        table.setSymbolFlags(key, flags);
    }

    public JsValue getPrimitive() {
        return primitive;
    }

    // A String wrapper's code units and its length are exotic own data properties. They are
    // materialised into the ordinary table here so every own-property path - reads, `in`, keys,
    // descriptors, freeze - sees them without each one re-deriving the exotic shape.
    public void setPrimitive(JsValue primitive) {
        this.primitive = primitive;
        if (primitive instanceof JsString string) {
            final var value = string.getValue();
            table.defineValue("length", new JsNumber(value.length()));
            table.setFlags("length", new PropertyFlags(false, false, false));
            for (var i = 0; i < value.length(); i++) {
                final var key = Integer.toString(i);
                table.defineValue(key, new JsString(String.valueOf(value.charAt(i))));
                table.setFlags(key, new PropertyFlags(false, true, false));
            }
        }
    }

    @Override
    public JsValue getProto() {
        return proto;
    }

    // A plain JsObject's proto field is Java null in two distinct situations that every reader has
    // to be able to tell apart: never explicitly linked (the common case - a plain function's own
    // auto-created "prototype" object, a freshly-built helper/result object - where the correct
    // reading is "not yet resolved, fall back to the realm's intrinsic default") versus deliberately
    // nulled out (Object.create(null), Object.setPrototypeOf(o, null), `{ __proto__: null }`, where
    // null is the real, terminal answer). setProto is the single choke point every one of those call
    // sites already goes through, so recording whether the last call passed null is enough to
    // disambiguate without auditing every "new JsObject()" site in the codebase.
    @Override
    public void setProto(JsValue proto) {
        this.proto = proto;
        this.protoExplicitlyNull = proto == null;
    }

    public boolean isProtoExplicitlyNull() {
        return protoExplicitlyNull;
    }

    public void defineAccessor(String key, JsValue getter, JsValue setter) {
        table.defineAccessor(key, getter, setter);
    }

    public JsValue getAccessorGetter(String key) {
        return table.getAccessorGetter(key);
    }

    public JsValue getAccessorSetter(String key) {
        return table.getAccessorSetter(key);
    }

    public void defineSymbolAccessor(JsSymbol key, JsValue getter, JsValue setter) {
        table.defineSymbolAccessor(key, getter, setter);
    }

    public JsValue getSymbolAccessorGetter(JsSymbol key) {
        return table.getSymbolAccessorGetter(key);
    }

    public JsValue getSymbolAccessorSetter(JsSymbol key) {
        return table.getSymbolAccessorSetter(key);
    }

    public boolean hasSymbolAccessor(JsSymbol key) {
        return table.hasSymbolAccessor(key);
    }

    public boolean hasAccessor(String key) {
        return table.hasAccessor(key);
    }
}
