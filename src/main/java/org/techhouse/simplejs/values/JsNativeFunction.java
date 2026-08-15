package org.techhouse.simplejs.values;

import java.util.List;
import java.util.function.BiFunction;

public final class JsNativeFunction extends JsValue implements JsCallableProperties {
    private final String name;
    private final BiFunction<JsValue, List<JsValue>, JsValue> implementation;
    private final CallablePropertyStore properties = new CallablePropertyStore();
    private JsValue boundTarget;
    private List<JsValue> boundArgs;
    private JsObject prototype;
    private JsValue ownProto;
    private int explicitLength = -1;

    public JsNativeFunction(String name, BiFunction<JsValue, List<JsValue>, JsValue> implementation) {
        this.name = name;
        this.implementation = implementation;
    }

    // Overrides the "length" metadata FunctionProtoBuiltins would otherwise report (0, since a
    // native's real parameter count isn't reflectively available) while keeping it non-writable,
    // non-enumerable, configurable - the spec shape for a builtin's length - by never putting it in
    // the mutable property map.
    public void setLength(int length) {
        this.explicitLength = length;
    }

    public boolean hasExplicitLength() {
        return explicitLength >= 0;
    }

    public int getExplicitLength() {
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

    public JsValue invoke(JsValue thisArg, List<JsValue> args) {
        return implementation.apply(thisArg, args);
    }

    @Override
    public void setProperty(String key, JsValue value) {
        properties.setProperty(key, value);
    }

    @Override
    public void setEnumerableProperty(String key, JsValue value) {
        properties.setEnumerableProperty(key, value);
    }

    @Override
    public JsValue getProperty(String key) {
        return properties.getProperty(key);
    }

    @Override
    public boolean hasProperty(String key) {
        return properties.hasProperty(key);
    }

    @Override
    public boolean deleteProperty(String key) {
        return properties.deleteProperty(key);
    }

    @Override
    public void markMetadataDeleted(String key) {
        properties.markMetadataDeleted(key);
    }

    @Override
    public boolean isMetadataDeleted(String key) {
        return properties.isMetadataDeleted(key);
    }

    @Override
    public List<String> propertyKeys() {
        return properties.propertyKeys();
    }

    @Override
    public List<String> enumerablePropertyKeys() {
        return properties.enumerablePropertyKeys();
    }

    public JsObject getPrototype() {
        return prototype;
    }

    public void setPrototype(JsObject prototype) {
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
